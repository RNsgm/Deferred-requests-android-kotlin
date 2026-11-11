package io.deferredrequests.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
public interface DeferredDao {

    @Query("SELECT * FROM deferred_request ORDER BY seq ASC, createdAt ASC")
    public suspend fun loadRequests(): List<DeferredRequestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun saveRequest(request: DeferredRequestEntity)

    @Query("DELETE FROM deferred_request WHERE id = :id")
    public suspend fun deleteRequest(id: String)

    @Query("SELECT id FROM deferred_request WHERE parentId = :parentId")
    public suspend fun childrenOf(parentId: String): List<String>

    @Query("SELECT MAX(seq) FROM deferred_request")
    public suspend fun maxSeq(): Long?

    @Query("DELETE FROM deferred_request WHERE owner IS NOT NULL AND owner != :owner")
    public suspend fun deleteRequestsOfOtherOwners(owner: String)

    @Query("SELECT * FROM deferred_answer")
    public suspend fun loadAnswers(): List<DeferredAnswerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun saveAnswer(answer: DeferredAnswerEntity)

    @Query("DELETE FROM deferred_answer WHERE id = :id")
    public suspend fun deleteAnswer(id: String)

    @Query(
        """
        DELETE FROM deferred_answer
        WHERE id NOT IN (SELECT parentId FROM deferred_request WHERE parentId IS NOT NULL)
        AND id NOT IN (SELECT id FROM deferred_answer ORDER BY createdAt DESC LIMIT :keep)
        """
    )
    public suspend fun pruneAnswers(keep: Int)

    @Query("SELECT id FROM deferred_answer")
    public suspend fun answerIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun markRejected(rejected: DeferredRejectedEntity)

    @Query("SELECT COUNT(*) FROM deferred_rejected WHERE id = :id")
    public suspend fun countRejected(id: String): Int

    @Query("DELETE FROM deferred_rejected WHERE rejectedAt < :threshold")
    public suspend fun pruneRejected(threshold: Long)
}
