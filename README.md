# deferred-requests

Очередь отложенных запросов к серверу для Kotlin и Android.

Запрос сначала пытаются отправить сразу. Если не вышло (нет сети, сервер недоступен,
истекла сессия), он сохраняется и уходит позже, сам, в правильном порядке, даже после
перезапуска приложения. Запросы могут зависеть друг от друга: дочерний уйдёт только
после родителя и получит его ответ.

## Модули

| Модуль | Что внутри | Зависимости |
|---|---|---|
| `deferred-core` | `DeferredQueue`, исходы, реестр исполнителей, хранилище в памяти | только `kotlinx-coroutines-core` |
| `deferred-gson` | `GsonRequestCodec` | `gson` |
| `deferred-room` | `RoomDeferredStore`, сущности и DAO | Room 2.6 (Android) |

Ядро — чистый JVM: его можно тестировать без эмулятора и использовать вне Android.

## Подключение

Пока библиотека не опубликована, проще всего подключить её как composite build.
В `settings.gradle` проекта:

```groovy
includeBuild("../DeferredRequests")
```

В `build.gradle` модуля:

```groovy
implementation "io.deferredrequests:deferred-core:0.1.0"
implementation "io.deferredrequests:deferred-gson:0.1.0"   // сериализация через Gson
implementation "io.deferredrequests:deferred-room:0.1.0"   // хранение очереди в Room
```

## Быстрый старт

```kotlin
// 1. Модель запроса — любой класс. DescribedRequest нужен только для экрана очереди.
data class CreateOrder(val items: List<Item>) : DescribedRequest {
    override val title get() = "Новый заказ"
}

data class AttachPhoto(val path: String)

// 2. Исполнитель: только отправка, без повторов и хранения.
class CreateOrderExecutor(private val api: Api) : DeferredExecutor<CreateOrder> {
    override suspend fun execute(request: CreateOrder, context: ExecutionContext): Response {
        val r = api.createOrder(request)
        return Response(r.code(), r.body()?.string().orEmpty(), r.errorBody()?.string())
    }
}

class AttachPhotoExecutor(private val api: Api) : DeferredExecutor<AttachPhoto> {
    override suspend fun execute(request: AttachPhoto, context: ExecutionContext): Response {
        // Ответ родителя — например, id созданного заказа.
        val order = gson.fromJson(context.requireParentAnswer(), OrderResponse::class.java)
        val file = File(request.path)
        if (!file.exists()) return Response.reject("Файл не найден: ${request.path}")

        val r = api.attachPhoto(order.id, file.asMultipart())
        return Response(r.code(), r.body()?.string().orEmpty(), r.errorBody()?.string())
    }
}

// 3. Очередь: один экземпляр на процесс (например, @Singleton в DI).
val queue = deferredQueue {
    store = RoomDeferredStore(db.deferredDao())   // без этой строки — хранилище в памяти
    codec = GsonRequestCodec()
    session = object : SessionProvider {
        override val owner get() = auth.userId    // запросы другого пользователя не уйдут под этим
        override val token get() = auth.token     // после 401 очередь ждёт новый токен
    }
    logger = MyLogger                             // своя реализация DeferredLogger
    register(CreateOrderExecutor(api))
    register(AttachPhotoExecutor(api))
}
queue.start()

// 4. Отправка: родитель, затем потомок, который получит ответ родителя.
val order = queue.submit(CreateOrder(items))
queue.submit(AttachPhoto(path), parentId = order.requestId)
```

`submit` возвращает `RequestStatus`:

```kotlin
when {
    status.isSuccess -> { /* отправлен */ }
    status.outcome.isPending -> { /* сохранён, уйдёт позже сам */ }
    else -> showError(status.body)   // отклонён навсегда, в body — причина
}
```

### Исполнитель

- Исключения ловить не обязательно: `IOException` считается обрывом сети, остальные —
  ошибкой исполнителя (см. таблицу ниже).
- Если у запроса есть родитель, его ответ лежит в `context.parentAnswer`. Пока ответа
  нет, исполнитель не вызывается.
- Ответ любого другого выполненного запроса: `context.answerOf(id)`.
- Исход можно задать явно: `Response.reject("причина")`, `Response.retry()`,
  `Response.success(body)`.

### Подписка в UI

```kotlin
// Число неотправленных запросов.
queue.pending.map { it.size }.collect { badge.text = "$it" }

// Итог каждой попытки.
queue.statuses.filter { it.outcome is Outcome.Rejected }.collect { showError(it.body) }

// Кнопка «Отправить сейчас» или появление сети.
scope.launch { queue.retryNow() }

// Убрать запрос вместе с потомками.
scope.launch { queue.cancel(id) }
```

## Правила очереди

- **Порядок.** Очередь обходится строго в порядке добавления. Пока предыдущие запросы
  не обработаны, новый их не обгоняет, иначе дочерний запрос ушёл бы раньше родителя.
  Запросы, которые ждут выдержки или родителя, новые не задерживают.
- **Родители.** Запрос с `parentId` выполняется только после успеха родителя. Если запрос
  ждёт родителя, его пропускают, и он не задерживает остальных.
- **Отказ уходит каскадом.** Если запрос отклонён, вместе с ним удаляется всё поддерево
  его потомков, и каждый получает статус в `statuses`. Отказ запоминается на
  `rejectedTtl`, так что потомок, созданный позже, отбрасывается сразу.
- **Нет сети.** Проход прерывается: остальные запросы упрутся в то же самое. В течение
  `retryInterval` после обрыва новые запросы ставятся в очередь сразу, без ожидания таймаута.
- **Ошибка сервера (408, 429, 5xx).** Запрос повторяется с экспоненциальной выдержкой
  (`retryInterval · 2^n`, не больше `maxBackoff`). Остальные запросы при этом идут мимо него.
- **Безнадёжные запросы** отсекаются по `maxAttempts` (считаются только ответы сервера,
  обрывы сети попыток не тратят) и по `requestTtl` (возраст запроса).
- **401.** Очередь встаёт на паузу, пока `SessionProvider.token` не сменится на
  непустой, или до вызова `resume()`. Данные при этом не теряются.
- **Смена пользователя.** При восстановлении очереди запросы другого `owner` удаляются.
- **Отмена корутины** не считается ошибкой запроса. А вот `withTimeout` внутри исполнителя
  обрабатывается как обрыв сети.

Как трактуются коды ответа (`HttpResponseClassifier`):

| Код | Исход |
|---|---|
| 2xx | `Success` |
| 0 / `IOException` | `Retry(networkUnavailable = true)` |
| 401 | `Unauthorized` |
| 408, 429, 5xx | `Retry(networkUnavailable = false)` |
| остальное, `FileNotFoundException`, прочие исключения | `Rejected` |

Свою таблицу можно задать через `ResponseClassifier`, свою обработку исключений — через
`ExceptionClassifier`.

## Настройки

`DeferredConfig`:

| Параметр | По умолчанию | Смысл |
|---|---|---|
| `retryInterval` | 10 с | период прохода; окно «сеть только что пропала» |
| `maxBackoff` | 10 мин | предел выдержки после ошибки сервера |
| `maxAttempts` | 500 | лимит ответов сервера с временной ошибкой |
| `requestTtl` | 14 суток | максимальный возраст запроса |
| `maxAnswers` | 1000 | сколько ответов хранить (ожидаемые потомками не вытесняются) |
| `rejectedTtl` | 30 суток | сколько помнить отказы |
| `pruneInterval` | 5 мин | как часто чистить ответы и отказы |

```kotlin
val queue = deferredQueue {
    config = DeferredConfig(retryInterval = 30_000, requestTtl = 7 * DeferredConfig.DAY)
    // …
}
```

## Room

Сущности подключаются к базе приложения:

```kotlin
@Database(
    entities = [/* … */, DeferredRequestEntity::class, DeferredAnswerEntity::class, DeferredRejectedEntity::class],
    version = N,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deferredDao(): DeferredDao
}
```

Таблицы называются `deferred_request`, `deferred_answer` и `deferred_rejected`.

Служебные поля запроса (id, родитель, порядок, попытки, владелец) хранятся в отдельных
колонках, а не в сериализованной модели. Поэтому модель может прятать их от сервера,
например через `@Transient`.

## Своё хранилище и кодек

- `DeferredStore` — постоянное хранилище очереди, ответов и отказов. Готовые реализации:
  `InMemoryDeferredStore` и `RoomDeferredStore`.
- `RequestCodec` — сериализация модели в строку и обратно. Готовая реализация:
  `GsonRequestCodec`.

Тип модели хранится по ключу из реестра. По умолчанию это полное имя класса; если модели
могут переименовываться или обфусцироваться (R8), задайте ключ явно:

```kotlin
register(CreateOrderExecutor(api), typeKey = "create-order")
```

Все исполнители нужно зарегистрировать до `start()`: запрос, восстановленный из
хранилища без исполнителя, отклоняется.

## Совместимость

- Kotlin 1.9 или новее (модули собраны с `apiVersion` / `languageVersion` 1.9).
- `kotlinx-coroutines-core` 1.7.3 и выше. Java 11, Android minSdk 21.

## Сборка

Gradle 8.14.3 и AGP 8.11.1. Демон Gradle закреплён на Java 17
(`gradle/gradle-daemon-jvm.properties`): сборку можно запускать из любой JDK, но
JDK 17 должна быть установлена.

```bash
./gradlew test                          # ядро и gson (JVM)
./gradlew :deferred-room:assembleRelease # Android-адаптер, нужен sdk.dir в local.properties
```
