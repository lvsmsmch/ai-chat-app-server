package com.lvsmsmch.aichat._common.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.mongodb.reactivestreams.client.ClientSession
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.coroutine.CoroutineCollection

enum class EntityType(val code: String, val recommendedIdLength: Int) {
    USER("user", 6),
    CHARACTER("character", 6),
    CHAT("chat", 10),
    MESSAGE("message", 10),
    REVIEW("review", 6),
    COMMENT("comment", 6);

    companion object {
        fun fromCode(code: String) = entries.first { it.code == code }
    }
}

@Serializable
data class DeletedIdsStatsDbo(
    @BsonId val entityType: String,
    val deletedIds: Set<String> = emptySet(),
    val createdAt: String = UtcTimestamp.now().toString(),
    val lastUpdated: String = UtcTimestamp.now().toString()
)

class DeletedIdsStatsRepository(
    private val collection: CoroutineCollection<DeletedIdsStatsDbo>
) {

    suspend fun isIdDeleted(entityType: EntityType, id: String): Boolean {
        val stats = collection.findOneById(entityType.code)
            ?: DeletedIdsStatsDbo(entityType = entityType.code).also {
                collection.insertOne(it)
            }

        return stats.deletedIds.contains(id)
    }

    suspend fun entityWasDeleted(session: ClientSession, entityType: EntityType, id: String) {
        val current = collection.findOneById(entityType.code)
            ?: DeletedIdsStatsDbo(entityType = entityType.code).also {
                collection.insertOne(it)
            }

        val new = current.copy(
            deletedIds = current.deletedIds + id,
            lastUpdated = UtcTimestamp.now().toString()
        )

        collection.replaceOneById(session, current.entityType, new)
    }

    suspend fun entitiesWereDeleted(session: ClientSession, entityType: EntityType, ids: List<String>) {
        if (ids.isEmpty()) return

        val current = collection.findOneById(entityType.code)
            ?: DeletedIdsStatsDbo(entityType = entityType.code).also {
                collection.insertOne(session, it)
            }

        val new = current.copy(
            deletedIds = current.deletedIds + ids,
            lastUpdated = UtcTimestamp.now().toString()
        )

        collection.replaceOneById(session, current.entityType, new)
    }
}