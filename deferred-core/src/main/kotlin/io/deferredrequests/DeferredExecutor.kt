package io.deferredrequests

import java.util.UUID

/**
 * Выполняет запросы одного типа.
 *
 * Исполнитель не заботится о повторах, порядке и хранении — только о том, чтобы
 * отправить запрос и вернуть [Response]. Исключения тоже допустимы: их разберёт
 * [ExceptionClassifier] (по умолчанию `IOException` — повтор, остальное — отказ).
 */
public fun interface DeferredExecutor<in T : Any> {
    public suspend fun execute(request: T, context: ExecutionContext): Response
}

/**
 * Что известно исполнителю о запросе в момент отправки.
 *
 * @param requestId Идентификатор запроса в очереди.
 * @param parentId Родительский запрос, если есть.
 * @param parentAnswer Тело успешного ответа родителя. Если [parentId] задан,
 * поле никогда не `null`: пока ответа нет, исполнитель не вызывается.
 * @param attempt Сколько раз сервер уже ответил на этот запрос временной ошибкой.
 */
public class ExecutionContext internal constructor(
    public val requestId: UUID,
    public val parentId: UUID?,
    public val parentAnswer: String?,
    public val attempt: Int,
    private val answers: (UUID) -> String?,
) {
    /** Ответ любого ранее выполненного запроса — для зависимостей помимо родителя. */
    public fun answerOf(id: UUID): String? = answers(id)

    /** Ответ родителя; бросает, если у запроса нет родителя. */
    public fun requireParentAnswer(): String =
        parentAnswer ?: throw IllegalStateException("У запроса $requestId нет родителя")
}
