package com.lvsmsmch.aichat._common.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toDeletedIdsStatsDbo
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

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

/**
 * Занятые когда-то id: генератор коротких id не должен выдать второй раз то,
 * что уже было удалено (иначе старые ссылки указали бы на новую сущность).
 */
class DeletedIdsStatsRepository {

    private suspend fun getOrCreate(entityType: EntityType): DeletedIdsStatsDbo = dbQuery {
        Tables.DeletedIdsStats.selectAll()
            .where { Tables.DeletedIdsStats.entityType eq entityType.code }
            .limit(1)
            .firstOrNull()
            ?.toDeletedIdsStatsDbo()
            ?: DeletedIdsStatsDbo(entityType = entityType.code).also { fresh ->
                Tables.DeletedIdsStats.insert { it.from(fresh) }
            }
    }

    suspend fun isIdDeleted(entityType: EntityType, id: String): Boolean =
        getOrCreate(entityType).deletedIds.contains(id)

    suspend fun entityWasDeleted(session: DbSession, entityType: EntityType, id: String) {
        entitiesWereDeleted(session, entityType, listOf(id))
    }

    suspend fun entitiesWereDeleted(session: DbSession, entityType: EntityType, ids: List<String>) {
        if (ids.isEmpty()) return
        val current = getOrCreate(entityType)
        val new = current.copy(
            deletedIds = current.deletedIds + ids,
            lastUpdated = UtcTimestamp.now().toString(),
        )
        dbQuery {
            Tables.DeletedIdsStats.update({ Tables.DeletedIdsStats.entityType eq new.entityType }) {
                it.from(new)
            }
        }
    }
}
