package io.deferredrequests

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Собирает [DeferredQueue]:
 *
 * ```
 * val queue = deferredQueue {
 *     store = RoomDeferredStore(db.deferredDao())
 *     codec = GsonRequestCodec()
 *     session = mySession
 *     register<MeterBody>(InstallMeterExecutor(api))
 *     register<ImageInsert>(ImageInsertExecutor(api))
 * }
 * queue.start()
 * ```
 */
public class DeferredQueueBuilder @PublishedApi internal constructor() {
    public var store: DeferredStore = InMemoryDeferredStore()
    public var codec: RequestCodec? = null
    public var config: DeferredConfig = DeferredConfig()
    public var session: SessionProvider = SessionProvider.None
    public var responseClassifier: ResponseClassifier = HttpResponseClassifier
    public var exceptionClassifier: ExceptionClassifier = DefaultExceptionClassifier
    public var logger: DeferredLogger = DeferredLogger.None
    public var scope: CoroutineScope? = null
    public var clock: () -> Long = System::currentTimeMillis

    public val registry: ExecutorRegistry = ExecutorRegistry()

    public inline fun <reified T : Any> register(
        executor: DeferredExecutor<T>,
        typeKey: String = T::class.java.name,
    ) {
        registry.register(T::class.java, executor, typeKey)
    }

    @PublishedApi
    internal fun build(): DeferredQueue = DeferredQueue(
        store = store,
        codec = requireNotNull(codec) { "Не задан codec" },
        registry = registry,
        config = config,
        session = session,
        responseClassifier = responseClassifier,
        exceptionClassifier = exceptionClassifier,
        logger = logger,
        scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = clock,
    )
}

public inline fun deferredQueue(block: DeferredQueueBuilder.() -> Unit): DeferredQueue =
    DeferredQueueBuilder().apply(block).build()
