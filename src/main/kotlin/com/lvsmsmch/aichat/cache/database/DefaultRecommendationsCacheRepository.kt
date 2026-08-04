package com.lvsmsmch.aichat.cache.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toDefaultRecommendationsCacheDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

@Serializable
data class DefaultRecommendationsCacheDbo(
    @BsonId val id: String = DEFAULT_CACHE_ID,
    val characterIds: List<String>,
    val updatedAt: String = UtcTimestamp.now().toString(),
    val version: String = ObjectId().toString()
)

private const val DEFAULT_CACHE_ID = "default_recommendations_cache"

class DefaultRecommendationsCacheRepository {

    suspend fun updateDefaultCache(characterIds: List<String>) {
        val cache = DefaultRecommendationsCacheDbo(
            characterIds = characterIds,
            updatedAt = UtcTimestamp.now().toString(),
        )
        dbQuery { Tables.DefaultRecommendationsCache.upsert { it.from(cache) } }
    }

    suspend fun getDefaultRecommendations(): List<String> =
        getDefaultCache()?.characterIds ?: emptyList()

    suspend fun getDefaultCache(): DefaultRecommendationsCacheDbo? = dbQuery {
        Tables.DefaultRecommendationsCache.selectAll()
            .where { Tables.DefaultRecommendationsCache.id eq DEFAULT_CACHE_ID }
            .limit(1)
            .firstOrNull()
            ?.toDefaultRecommendationsCacheDbo()
    }

    suspend fun hasRecentCache(maxAgeHours: Long = 1): Boolean {
        val cache = getDefaultCache() ?: return false
        return UtcTimestamp.parse(cache.updatedAt)
            .isAfter(UtcTimestamp.now().subtractHours(maxAgeHours))
    }
}
