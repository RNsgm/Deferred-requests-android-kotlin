package io.deferredrequests

import java.util.UUID

/**
 * Хранилище в памяти: для тестов и для очередей, которым не нужно переживать
 * перезапуск процесса.
 */
public class InMemoryDeferredStore : DeferredStore {

    private data class Answer(val body: String, val createdAt: Long)
    private data class Rejection(val reason: String, val code: Int?, val rejectedAt: Long)

    private val lock = Any()
    private val requests = LinkedHashMap<UUID, StoredRequest>()
    private val answers = LinkedHashMap<UUID, Answer>()
    private val rejected = LinkedHashMap<UUID, Rejection>()

    override suspend fun loadRequests(): List<StoredRequest> =
        synchronized(lock) { requests.values.sortedWith(compareBy({ it.seq }, { it.createdAt })) }

    override suspend fun saveRequest(request: StoredRequest) {
        synchronized(lock) { requests[request.id] = request }
    }

    override suspend fun deleteRequest(id: UUID) {
        synchronized(lock) { requests.remove(id) }
    }

    override suspend fun childrenOf(parentId: UUID): List<UUID> =
        synchronized(lock) { requests.values.filter { it.parentId == parentId }.map { it.id } }

    override suspend fun maxSeq(): Long? =
        synchronized(lock) { requests.values.maxOfOrNull { it.seq } }

    override suspend fun deleteRequestsOfOtherOwners(owner: String) {
        synchronized(lock) {
            requests.values.removeAll { it.owner != null && it.owner != owner }
        }
    }

    override suspend fun loadAnswers(): Map<UUID, String> =
        synchronized(lock) { answers.mapValues { it.value.body } }

    override suspend fun saveAnswer(id: UUID, body: String, createdAt: Long) {
        synchronized(lock) { answers[id] = Answer(body, createdAt) }
    }

    override suspend fun deleteAnswer(id: UUID) {
        synchronized(lock) { answers.remove(id) }
    }

    override suspend fun pruneAnswers(keep: Int): Set<UUID> = synchronized(lock) {
        val awaited = requests.values.mapNotNull { it.parentId }.toSet()
        val fresh = answers.entries
            .sortedByDescending { it.value.createdAt }
            .take(keep)
            .map { it.key }
            .toSet()
        answers.keys.retainAll { it in awaited || it in fresh }
        answers.keys.toSet()
    }

    override suspend fun markRejected(id: UUID, reason: String, code: Int?, rejectedAt: Long) {
        synchronized(lock) { rejected[id] = Rejection(reason, code, rejectedAt) }
    }

    override suspend fun isRejected(id: UUID): Boolean = synchronized(lock) { id in rejected }

    override suspend fun pruneRejected(olderThan: Long) {
        synchronized(lock) { rejected.values.removeAll { it.rejectedAt < olderThan } }
    }
}
