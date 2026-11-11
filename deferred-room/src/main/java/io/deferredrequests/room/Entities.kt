package io.deferredrequests.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Запрос, ожидающий отправки. */
@Entity(
    tableName = "deferred_request",
    indices = [Index("parentId"), Index("seq")],
)
public data class DeferredRequestEntity(
    @PrimaryKey val id: String,
    val typeKey: String,
    val payload: String,
    val parentId: String?,
    @ColumnInfo(defaultValue = "0") val seq: Long,
    @ColumnInfo(defaultValue = "0") val attempts: Int,
    @ColumnInfo(defaultValue = "0") val createdAt: Long,
    val owner: String?,
)

/** Ответ сервера на выполненный запрос — для дочерних запросов. */
@Entity(tableName = "deferred_answer")
public data class DeferredAnswerEntity(
    @PrimaryKey val id: String,
    val body: String,
    val createdAt: Long,
)

/** Запрос, отклонённый навсегда: его поздние потомки отбрасываются сразу. */
@Entity(tableName = "deferred_rejected")
public data class DeferredRejectedEntity(
    @PrimaryKey val id: String,
    val reason: String,
    val code: Int?,
    val rejectedAt: Long,
)
