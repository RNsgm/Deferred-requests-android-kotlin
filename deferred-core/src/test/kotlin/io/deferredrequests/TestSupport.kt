package io.deferredrequests

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import java.io.IOException

data class Note(val text: String) : DescribedRequest {
    override val title: String get() = "note:$text"
}

data class Photo(val path: String)

/** Кодек «строка в строку» для двух тестовых моделей. */
object TestCodec : RequestCodec {
    override fun encode(request: Any): String = when (request) {
        is Note -> request.text
        is Photo -> request.path
        else -> error("неизвестная модель $request")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> decode(payload: String, type: Class<T>): T = when (type) {
        Note::class.java -> Note(payload) as T
        Photo::class.java -> Photo(payload) as T
        else -> error("неизвестный тип $type")
    }
}

/**
 * Исполнитель по сценарию: каждый вызов берёт следующий шаг, последний шаг повторяется.
 * Шаг — либо [Response], либо исключение.
 */
class ScriptedExecutor<T : Any>(vararg steps: Any) : DeferredExecutor<T> {
    private val script = steps.toMutableList()
    val calls = mutableListOf<Pair<T, ExecutionContext>>()

    fun then(vararg steps: Any) {
        script.clear()
        script.addAll(steps)
    }

    override suspend fun execute(request: T, context: ExecutionContext): Response {
        calls += request to context
        val step = if (script.size > 1) script.removeAt(0) else script.first()
        return when (step) {
            is Response -> step
            is Throwable -> throw step
            is Function0<*> -> step() as Response
            else -> error("плохой шаг $step")
        }
    }
}

val ok = Response(201, "{\"id\":1}")
fun ok(body: String) = Response(200, body)
val offline = IOException("нет сети")
val serverDown = Response(503, "", "maintenance")

class MutableSession(override var owner: String? = null, override var token: String? = "t1") : SessionProvider

fun TestScope.queue(
    store: DeferredStore = InMemoryDeferredStore(),
    config: DeferredConfig = DeferredConfig(),
    session: SessionProvider = SessionProvider.None,
    scope: CoroutineScope = backgroundScope,
    register: ExecutorRegistry.() -> Unit,
): DeferredQueue = DeferredQueue(
    store = store,
    codec = TestCodec,
    registry = ExecutorRegistry().apply(register),
    config = config,
    session = session,
    scope = scope,
    clock = { testScheduler.currentTime },
)
