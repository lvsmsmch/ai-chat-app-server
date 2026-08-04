package com.lvsmsmch.aichat.cache.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toCharacterListCopyDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

class CharacterListCopyRepository {

    suspend fun upsert(characterListCopyDbo: CharacterListCopyDbo) {
        dbQuery { Tables.CharacterListCopies.upsert { it.from(characterListCopyDbo) } }
    }

    suspend fun updatePosition(userId: String, deviceId: String, listType: String, newPosition: Int) {
        val id = copyId(userId, deviceId, listType)
        dbQuery {
            Tables.CharacterListCopies.update({ Tables.CharacterListCopies.id eq id }) {
                it[Tables.CharacterListCopies.currentPosition] = newPosition
                it[Tables.CharacterListCopies.lastAccessedAt] = UtcTimestamp.now().toString()
            }
        }
    }

    suspend fun getExistingCopy(
        userId: String,
        deviceId: String,
        listType: String,
    ): CharacterListCopyDbo? = dbQuery {
        Tables.CharacterListCopies.selectAll()
            .where { Tables.CharacterListCopies.id eq copyId(userId, deviceId, listType) }
            .limit(1)
            .firstOrNull()
            ?.toCharacterListCopyDbo()
    }

    suspend fun deleteSessionCopy(userId: String, deviceId: String, listType: String) {
        dbQuery {
            Tables.CharacterListCopies.deleteWhere {
                Tables.CharacterListCopies.id eq copyId(userId, deviceId, listType)
            }
        }
    }

    suspend fun deleteAllUserCopies(userId: String) {
        dbQuery {
            Tables.CharacterListCopies.deleteWhere { Tables.CharacterListCopies.userId eq userId }
        }
    }

    suspend fun deleteAllSearchCopiesForUserDevice(userId: String, deviceId: String) {
        dbQuery {
            Tables.CharacterListCopies.deleteWhere {
                (Tables.CharacterListCopies.userId eq userId) and
                    (Tables.CharacterListCopies.deviceId eq deviceId) and
                    (Tables.CharacterListCopies.listType like "search_%")
            }
        }
    }

    suspend fun deleteOldCopies(): Long = dbQuery {
        val dayAgo = UtcTimestamp.now().subtractHours(24)
        Tables.CharacterListCopies.deleteWhere {
            Tables.CharacterListCopies.lastAccessedAt less dayAgo.toString()
        }.toLong()
    }

    suspend fun getCopyStats(): SessionCopyStats = dbQuery {
        val now = UtcTimestamp.now()
        val total = Tables.CharacterListCopies.selectAll().count()
        val active = Tables.CharacterListCopies.selectAll()
            .where {
                Tables.CharacterListCopies.lastAccessedAt greaterEq now.subtractHours(1).toString()
            }
            .count()
        val old = Tables.CharacterListCopies.selectAll()
            .where {
                Tables.CharacterListCopies.lastAccessedAt less now.subtractHours(24).toString()
            }
            .count()
        val search = Tables.CharacterListCopies.selectAll()
            .where { Tables.CharacterListCopies.listType like "search_%" }
            .count()
        val category = Tables.CharacterListCopies.selectAll()
            .where { Tables.CharacterListCopies.listType like "category_%" }
            .count()
        val personalized = Tables.CharacterListCopies.selectAll()
            .where { Tables.CharacterListCopies.listType eq "personalized" }
            .count()
        SessionCopyStats(
            totalCopies = total.toInt(),
            activeCopies = active.toInt(),
            oldCopies = old.toInt(),
            searchCopies = search.toInt(),
            categoryCopies = category.toInt(),
            personalizedCopies = personalized.toInt(),
        )
    }

    private fun copyId(userId: String, deviceId: String, listType: String) =
        "${userId}_${deviceId}_${listType}"

    data class SessionCopyResult(
        val characterIds: List<String>,
        val nextPosition: Int,
        val hasMore: Boolean,
        val totalCount: Int
    )

    data class SessionCopyStats(
        val totalCopies: Int,
        val activeCopies: Int,
        val oldCopies: Int,
        val searchCopies: Int,
        val categoryCopies: Int,
        val personalizedCopies: Int
    )
}
