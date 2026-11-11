package io.deferredrequests

import java.util.UUID

/**
 * Запись очереди в хранилище.
 *
 * Служебные поля хранятся отдельно от [payload]: модель может прятать их от сервера,
 * и тогда в сериализованном виде их бы не было.
 */
public data class StoredRequest(
    val id: UUID,
    /** Ключ типа из [ExecutorRegistry]. */
    val typeKey: String,
    val payload: String,
    val parentId: UUID?,
    /** Сквозной порядковый номер: очередь отправляется строго по нему. */
    val seq: Long,
    val attempts: Int,
    val createdAt: Long,
    val owner: String?,
)

/**
 * Постоянное хранилище очереди: сами запросы, ответы сервера (для дочерних запросов)
 * и отклонённые запросы (чтобы отбрасывать их поздних потомков).
 *
 * Очередь вызывает методы последовательно, под своей блокировкой.
 */
public interface DeferredStore {

    // region Очередь

    /** Все запросы в порядке [StoredRequest.seq]. */
    public suspend fun loadRequests(): List<StoredRequest>

    /** Вставляет или заменяет запрос с тем же [StoredRequest.id]. */
    public suspend fun saveRequest(request: StoredRequest)

    public suspend fun deleteRequest(id: UUID)

    /** Идентификаторы запросов, чей родитель — [parentId]. */
    public suspend fun childrenOf(parentId: UUID): List<UUID>

    public suspend fun maxSeq(): Long?

    /** Удаляет запросы других владельцев (запросы без владельца не трогаются). */
    public suspend fun deleteRequestsOfOtherOwners(owner: String)

    // endregion

    // region Ответы

    public suspend fun loadAnswers(): Map<UUID, String>

    public suspend fun saveAnswer(id: UUID, body: String, createdAt: Long)

    public suspend fun deleteAnswer(id: UUID)

    /**
     * Оставляет [keep] самых свежих ответов. Ответ, которого ждёт хотя бы один запрос
     * из очереди, не удаляется независимо от возраста.
     * @return Идентификаторы оставшихся ответов.
     */
    public suspend fun pruneAnswers(keep: Int): Set<UUID>

    // endregion

    // region Отказы

    public suspend fun markRejected(id: UUID, reason: String, code: Int?, rejectedAt: Long)

    public suspend fun isRejected(id: UUID): Boolean

    public suspend fun pruneRejected(olderThan: Long)

    // endregion
}
