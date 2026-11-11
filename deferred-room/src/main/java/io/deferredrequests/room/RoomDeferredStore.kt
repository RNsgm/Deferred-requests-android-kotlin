package io.deferredrequests.room

import io.deferredrequests.DeferredStore
import io.deferredrequests.StoredRequest
import java.util.UUID

/**
 * Хранилище очереди на Room.
 *
 * Сущности ([DeferredRequestEntity], [DeferredAnswerEntity], [DeferredRejectedEntity])
 * подключаются к базе приложения:
 *
 * ```
 * @Database(entities = [..., DeferredRequestEntity::class, DeferredAnswerEntity::class,
 *     DeferredRejectedEntity::class], version = N)
 * abstract class AppDatabase : RoomDatabase() {
 *     abstract fun deferredDao(): DeferredDao
 * }
 * ```
 */
public class RoomDeferredStore(private val dao: DeferredDao) : DeferredStore {

    override suspend fun loadRequests(): List<StoredRequest> = dao.loadRequests().mapNotNull { e ->
        val id = e.id.toUuidOrNull() ?: return@mapNotNull null
        StoredRequest(
            id = id,
            typeKey = e.typeKey,
            payload = e.payload,
            parentId = e.parentId?.toUuidOrNull(),
            seq = e.seq,
            attempts = e.attempts,
            createdAt = e.createdAt,
            owner = e.owner,
        )
    }

    override suspend fun saveRequest(request: StoredRequest) {
        dao.saveRequest(
            DeferredRequestEntity(
                id = request.id.toString(),
                typeKey = request.typeKey,
                payload = request.payload,
                parentId = request.parentId?.toString(),
                seq = request.seq,
                attempts = request.attempts,
                createdAt = request.createdAt,
                owner = request.owner,
            )
        )
    }

    override suspend fun deleteRequest(id: UUID): Unit = dao.deleteRequest(id.toString())

    override suspend fun childrenOf(parentId: UUID): List<UUID> =
        dao.childrenOf(parentId.toString()).mapNotNull { it.toUuidOrNull() }

    override suspend fun maxSeq(): Long? = dao.maxSeq()

    override suspend fun deleteRequestsOfOtherOwners(owner: String): Unit =
        dao.deleteRequestsOfOtherOwners(owner)

    override suspend fun loadAnswers(): Map<UUID, String> =
        dao.loadAnswers().mapNotNull { e -> e.id.toUuidOrNull()?.let { it to e.body } }.toMap()

    override suspend fun saveAnswer(id: UUID, body: String, createdAt: Long): Unit =
        dao.saveAnswer(DeferredAnswerEntity(id.toString(), body, createdAt))

    override suspend fun deleteAnswer(id: UUID): Unit = dao.deleteAnswer(id.toString())

    override suspend fun pruneAnswers(keep: Int): Set<UUID> {
        dao.pruneAnswers(keep)
        return dao.answerIds().mapNotNull { it.toUuidOrNull() }.toSet()
    }

    override suspend fun markRejected(id: UUID, reason: String, code: Int?, rejectedAt: Long): Unit =
        dao.markRejected(DeferredRejectedEntity(id.toString(), reason, code, rejectedAt))

    override suspend fun isRejected(id: UUID): Boolean = dao.countRejected(id.toString()) > 0

    override suspend fun pruneRejected(olderThan: Long): Unit = dao.pruneRejected(olderThan)

    private fun String.toUuidOrNull(): UUID? = try {
        UUID.fromString(this)
    } catch (e: IllegalArgumentException) {
        null
    }
}
