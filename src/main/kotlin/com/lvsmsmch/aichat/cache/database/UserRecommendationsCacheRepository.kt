package com.lvsmsmch.aichat.cache.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toUserRecommendationsCacheDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

@Serializable
data class UserRecommendationsCacheDbo(
    @BsonId val userId: String,
    val characterIds: List<String>,
    val updatedAt: String = UtcTimestamp.now().toString(),
    val version: String = ObjectId().toString()
)

class UserRecommendationsCacheRepository {

    suspend fun upsertUserCache(userId: String, characterIds: List<String>) {
        val cache = UserRecommendationsCacheDbo(
            userId = userId,
            characterIds = characterIds,
            updatedAt = UtcTimestamp.now().toString(),
        )
        dbQuery { Tables.UserRecommendationsCache.upsert { it.from(cache) } }
    }

    suspend fun getUserCache(userId: String): UserRecommendationsCacheDbo? = dbQuery {
        Tables.UserRecommendationsCache.selectAll()
            .where { Tables.UserRecommendationsCache.userId eq userId }
            .limit(1)
            .firstOrNull()
            ?.toUserRecommendationsCacheDbo()
    }

    suspend fun getCachedRecommendations(userId: String): List<String> =
        getUserCache(userId)?.characterIds ?: emptyList()

    suspend fun hasFreshCache(userId: String, ttlHours: Long): Boolean {
        val cache = getUserCache(userId) ?: return false
        return UtcTimestamp.parse(cache.updatedAt).addHours(ttlHours).isAfter(UtcTimestamp.now())
    }

    suspend fun deleteUserCache(userId: String) {
        dbQuery {
            Tables.UserRecommendationsCache.deleteWhere {
                Tables.UserRecommendationsCache.userId eq userId
            }
        }
    }

    suspend fun deleteInactiveUserCaches(): Long = dbQuery {
        val monthAgo = UtcTimestamp.now().subtractDays(30)
        Tables.UserRecommendationsCache.deleteWhere {
            Tables.UserRecommendationsCache.updatedAt less monthAgo.toString()
        }.toLong()
    }

    suspend fun getCacheStats(): CacheStats = dbQuery {
        val now = UtcTimestamp.now()
        val total = Tables.UserRecommendationsCache.selectAll().count()
        val fresh = Tables.UserRecommendationsCache.selectAll()
            .where {
                Tables.UserRecommendationsCache.updatedAt greaterEq now.subtractHours(24).toString()
            }
            .count()
        val old = Tables.UserRecommendationsCache.selectAll()
            .where { Tables.UserRecommendationsCache.updatedAt less now.subtractDays(7).toString() }
            .count()
        CacheStats(
            totalCaches = total.toInt(),
            freshCaches = fresh.toInt(),
            oldCaches = old.toInt(),
        )
    }
}

data class CacheStats(
    val totalCaches: Int,
    val freshCaches: Int,
    val oldCaches: Int
)
