# deferred-requests

**English** | [Русский](README.ru.md)

A queue of deferred server requests for Kotlin and Android.

Each request is sent right away when possible. If that fails (no network, server down,
expired session), the request is persisted and sent later, automatically and in the
right order, even across app restarts. Requests can depend on each other: a child is
sent only after its parent succeeds and receives the parent's response.

## Modules

| Module | Contents | Dependencies |
|---|---|---|
| `deferred-core` | `DeferredQueue`, outcomes, executor registry, in-memory store | `kotlinx-coroutines-core` only |
| `deferred-gson` | `GsonRequestCodec` | `gson` |
| `deferred-room` | `RoomDeferredStore`, entities and DAO | Room 2.6 (Android) |

The core is plain JVM: you can test it without an emulator and use it outside Android.

## Installation

The library is published via [JitPack](https://jitpack.io/#RNsgm/Deferred-requests-android-kotlin).
Add the repository in `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url "https://jitpack.io" }
    }
}
```

and the modules you need in your module's `build.gradle`:

```groovy
implementation "com.github.RNsgm.Deferred-requests-android-kotlin:deferred-core:0.1.0"
implementation "com.github.RNsgm.Deferred-requests-android-kotlin:deferred-gson:0.1.0"   // Gson serialization
implementation "com.github.RNsgm.Deferred-requests-android-kotlin:deferred-room:0.1.0"   // Room persistence
```

To work on the library and an app side by side, use a composite build instead:
`includeBuild("../Deferred-requests-android-kotlin")` in `settings.gradle`, with
dependencies `io.deferredrequests:deferred-core:0.1.0` and so on.

## Quick start

```kotlin
// 1. A request model can be any class. DescribedRequest is only for queue screens.
data class CreateOrder(val items: List<Item>) : DescribedRequest {
    override val title get() = "New order"
}

data class AttachPhoto(val path: String)

// 2. An executor only sends the request: no retries, no persistence.
class CreateOrderExecutor(private val api: Api) : DeferredExecutor<CreateOrder> {
    override suspend fun execute(request: CreateOrder, context: ExecutionContext): Response {
        val r = api.createOrder(request)
        return Response(r.code(), r.body()?.string().orEmpty(), r.errorBody()?.string())
    }
}

class AttachPhotoExecutor(private val api: Api) : DeferredExecutor<AttachPhoto> {
    override suspend fun execute(request: AttachPhoto, context: ExecutionContext): Response {
        // The parent's response, e.g. the id of the created order.
        val order = gson.fromJson(context.requireParentAnswer(), OrderResponse::class.java)
        val file = File(request.path)
        if (!file.exists()) return Response.reject("File not found: ${request.path}")

        val r = api.attachPhoto(order.id, file.asMultipart())
        return Response(r.code(), r.body()?.string().orEmpty(), r.errorBody()?.string())
    }
}

// 3. The queue: one instance per process (e.g. a @Singleton in DI).
val queue = deferredQueue {
    store = RoomDeferredStore(db.deferredDao())   // omit for an in-memory store
    codec = GsonRequestCodec()
    session = object : SessionProvider {
        override val owner get() = auth.userId    // another user's requests won't be sent under this one
        override val token get() = auth.token     // after a 401 the queue waits for a new token
    }
    logger = MyLogger                             // your DeferredLogger implementation
    register(CreateOrderExecutor(api))
    register(AttachPhotoExecutor(api))
}
queue.start()

// 4. Submit a parent, then a child that will receive the parent's response.
val order = queue.submit(CreateOrder(items))
queue.submit(AttachPhoto(path), parentId = order.requestId)
```

`submit` returns a `RequestStatus`:

```kotlin
when {
    status.isSuccess -> { /* sent */ }
    status.outcome.isPending -> { /* saved, will be sent later automatically */ }
    else -> showError(status.body)   // rejected for good; body holds the reason
}
```

### Executors

- You don't have to catch exceptions: an `IOException` counts as a network failure, any
  other exception as an executor error (see the table below).
- If the request has a parent, the parent's response is in `context.parentAnswer`. The
  executor isn't called until that response exists.
- The response of any other completed request: `context.answerOf(id)`.
- You can set the outcome explicitly: `Response.reject("reason")`, `Response.retry()`,
  `Response.success(body)`.

### Observing from the UI

```kotlin
// Number of unsent requests.
queue.pending.map { it.size }.collect { badge.text = "$it" }

// The result of every attempt.
queue.statuses.filter { it.outcome is Outcome.Rejected }.collect { showError(it.body) }

// A "Send now" button, or the network coming back.
scope.launch { queue.retryNow() }

// Remove a request together with its descendants.
scope.launch { queue.cancel(id) }
```

## Queue rules

- **Order.** The queue is processed strictly in the order requests were added. A new
  request never overtakes unprocessed earlier ones, otherwise a child could be sent before
  its parent. Requests waiting for backoff or for a parent don't hold new ones back.
- **Parents.** A request with a `parentId` runs only after its parent succeeds. While it
  waits, it is skipped and doesn't block the rest of the queue.
- **Cascading rejection.** When a request is rejected, its whole subtree of descendants is
  removed with it, and each of them gets a status in `statuses`. The rejection is
  remembered for `rejectedTtl`, so a descendant created later is dropped immediately.
- **No network.** The pass stops: the remaining requests would hit the same wall. For
  `retryInterval` after a network failure, new requests are queued right away instead of
  waiting for a timeout.
- **Server error (408, 429, 5xx).** The request is retried with exponential backoff
  (`retryInterval · 2^n`, capped at `maxBackoff`). Other requests go past it meanwhile.
- **Hopeless requests** are cut off by `maxAttempts` (only server responses count; network
  failures don't use up attempts) and by `requestTtl` (request age).
- **401.** The queue pauses until `SessionProvider.token` changes to a non-empty value, or
  until you call `resume()`. No data is lost.
- **User change.** When the queue is restored, requests from a different `owner` are deleted.
- **Coroutine cancellation** is not treated as a request failure. A `withTimeout` inside an
  executor, however, is treated as a network failure.

How response codes are interpreted (`HttpResponseClassifier`):

| Code | Outcome |
|---|---|
| 2xx | `Success` |
| 0 / `IOException` | `Retry(networkUnavailable = true)` |
| 401 | `Unauthorized` |
| 408, 429, 5xx | `Retry(networkUnavailable = false)` |
| anything else, `FileNotFoundException`, other exceptions | `Rejected` |

Provide your own mapping with a `ResponseClassifier`, and your own exception handling with
an `ExceptionClassifier`.

## Configuration

`DeferredConfig`:

| Parameter | Default | Meaning |
|---|---|---|
| `retryInterval` | 10 s | pass period; the "network just dropped" window |
| `maxBackoff` | 10 min | backoff cap after a server error |
| `maxAttempts` | 500 | limit on server responses with a temporary error |
| `requestTtl` | 14 days | maximum request age |
| `maxAnswers` | 1000 | how many responses to keep (ones awaited by descendants are never evicted) |
| `rejectedTtl` | 30 days | how long to remember rejections |
| `pruneInterval` | 5 min | how often responses and rejections are cleaned up |

```kotlin
val queue = deferredQueue {
    config = DeferredConfig(retryInterval = 30_000, requestTtl = 7 * DeferredConfig.DAY)
    // …
}
```

## Room

Add the entities to your app's database:

```kotlin
@Database(
    entities = [/* … */, DeferredRequestEntity::class, DeferredAnswerEntity::class, DeferredRejectedEntity::class],
    version = N,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deferredDao(): DeferredDao
}
```

The tables are named `deferred_request`, `deferred_answer` and `deferred_rejected`.

The request's bookkeeping fields (id, parent, order, attempts, owner) are stored in
separate columns, not in the serialized model. So a model can hide them from the server,
for example with `@Transient`.

## Custom store and codec

- `DeferredStore` persists the queue, responses and rejections. Built-in implementations:
  `InMemoryDeferredStore` and `RoomDeferredStore`.
- `RequestCodec` serializes a model to a string and back. Built-in implementation:
  `GsonRequestCodec`.

A model's type is stored under a key from the registry. By default it's the fully
qualified class name; if your models may be renamed or obfuscated (R8), set the key
explicitly:

```kotlin
register(CreateOrderExecutor(api), typeKey = "create-order")
```

Register all executors before `start()`: a request restored from the store without an
executor is rejected.

## Compatibility

- Kotlin 1.9 or newer (the modules are built with `apiVersion` / `languageVersion` 1.9).
- `kotlinx-coroutines-core` 1.7.3 or newer. Java 11, Android minSdk 21.

## Building

Gradle 8.14.3 and AGP 8.11.1. The Gradle daemon is pinned to Java 17
(`gradle/gradle-daemon-jvm.properties`): you can launch the build from any JDK, but
JDK 17 must be installed.

```bash
./gradlew test                          # core and gson (JVM)
./gradlew :deferred-room:assembleRelease # Android adapter; needs sdk.dir in local.properties
```

## License

Apache License 2.0, see [LICENSE](LICENSE).
