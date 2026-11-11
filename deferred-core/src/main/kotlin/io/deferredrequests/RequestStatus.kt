package io.deferredrequests

import java.util.UUID

/**
 * Итог одной попытки отправить запрос.
 *
 * `isSuccess == false` сам по себе не говорит, потерян запрос или отложен:
 * смотрите [outcome] ([Outcome.isPending] — сохранён и будет отправлен).
 */
public data class RequestStatus(
    val requestId: UUID,
    val outcome: Outcome,
    val code: Int? = null,
    val body: String? = null,
    /** Модель запроса — чтобы подписчик [DeferredQueue.statuses] понял, о чём речь. */
    val request: Any? = null,
) {
    val isSuccess: Boolean get() = outcome == Outcome.Success
}

/** Запрос, ожидающий отправки. */
public data class PendingRequest(
    val id: UUID,
    val parentId: UUID?,
    val request: Any,
    val title: String,
    val description: String,
    /** Время постановки в очередь, мс. */
    val createdAt: Long,
    /** Сколько раз сервер уже ответил временной ошибкой. */
    val attempts: Int,
)
