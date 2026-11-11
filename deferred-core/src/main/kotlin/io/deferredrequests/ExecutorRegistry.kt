package io.deferredrequests

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Связывает тип модели запроса с исполнителем и ключом типа в хранилище.
 *
 * Ключ по умолчанию — полное имя класса. Его стоит задать явно, если модели могут
 * переименовываться или обфусцироваться: иначе сохранённая очередь перестанет
 * читаться после обновления приложения.
 *
 * Все исполнители нужно зарегистрировать до первого запуска очереди: запрос,
 * восстановленный из хранилища без исполнителя, отклоняется.
 */
public class ExecutorRegistry {

    public class Registration<T : Any> internal constructor(
        public val type: Class<T>,
        public val typeKey: String,
        internal val executor: DeferredExecutor<T>,
    )

    private val byClass = ConcurrentHashMap<Class<*>, Registration<*>>()
    private val byKey = ConcurrentHashMap<String, Registration<*>>()

    public fun <T : Any> register(
        type: Class<T>,
        executor: DeferredExecutor<T>,
        typeKey: String = type.name,
    ): ExecutorRegistry = apply {
        synchronized(this) {
            val existing = byKey[typeKey]
            require(existing == null || existing.type == type) {
                "Ключ $typeKey уже занят типом ${existing!!.type.name}"
            }
            byClass[type]?.let { byKey.remove(it.typeKey) }
            val registration = Registration(type, typeKey, executor)
            byClass[type] = registration
            byKey[typeKey] = registration
        }
    }

    public fun <T : Any> register(
        type: KClass<T>,
        executor: DeferredExecutor<T>,
        typeKey: String = type.java.name,
    ): ExecutorRegistry = register(type.java, executor, typeKey)

    public inline fun <reified T : Any> register(
        executor: DeferredExecutor<T>,
        typeKey: String = T::class.java.name,
    ): ExecutorRegistry = register(T::class.java, executor, typeKey)

    public fun forRequest(request: Any): Registration<*>? = byClass[request::class.java]

    public fun forKey(typeKey: String): Registration<*>? = byKey[typeKey]
}

@Suppress("UNCHECKED_CAST")
internal suspend fun ExecutorRegistry.Registration<*>.run(
    request: Any,
    context: ExecutionContext,
): Response = (this as ExecutorRegistry.Registration<Any>).executor.execute(request, context)
