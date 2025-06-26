package com.lvsmsmch.aichat.cache.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.mongodb.client.model.ReplaceOptions
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class UserRecommendationsCacheDbo(
    @BsonId val userId: String,
    val characterIds: List<String>,
    val updatedAt: UtcTimestamp = UtcTimestamp.now(),
    val version: String = ObjectId().toString()
)

class UserRecommendationsCacheRepository(
    private val collection: CoroutineCollection<UserRecommendationsCacheDbo>
) {

    /**
     * FLOW
     */
    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    /**
     * CREATE / UPDATE
     */
    suspend fun upsertUserCache(userId: String, characterIds: List<String>) {
        val cache = UserRecommendationsCacheDbo(
            userId = userId,
            characterIds = characterIds,
            updatedAt = UtcTimestamp.now()
        )
        
        collection.replaceOneById(userId, cache, ReplaceOptions().upsert(true))
    }

    /**
     * READ
     */
    suspend fun getUserCache(userId: String): UserRecommendationsCacheDbo? {
        return collection.findOneById(userId)
    }

    suspend fun getCachedRecommendations(userId: String): List<String> {
        return collection.findOneById(userId)?.characterIds ?: emptyList()
    }

    suspend fun hasFreshCache(userId: String, ttlHours: Long): Boolean {
        val cache = collection.findOneById(userId) ?: return false
        val expirationTime = cache.updatedAt.addHours(ttlHours)
        return expirationTime.isAfter(UtcTimestamp.now())
    }

    /**
     * DELETE
     */
    suspend fun deleteUserCache(userId: String) {
        collection.deleteOneById(userId)
    }

    suspend fun deleteInactiveUserCaches(): Long {
        val monthAgo = UtcTimestamp.now().subtractDays(30)
        val result = collection.deleteMany(
            UserRecommendationsCacheDbo::updatedAt lt monthAgo
        )
        return result.deletedCount
    }

    /**
     * UTILITY
     */
    suspend fun getCacheStats(): CacheStats {
        val totalCaches = collection.countDocuments()
        val now = UtcTimestamp.now()
        
        val freshCaches = collection.countDocuments(
            UserRecommendationsCacheDbo::updatedAt gte now.subtractHours(24)
        )
        
        val oldCaches = collection.countDocuments(
            UserRecommendationsCacheDbo::updatedAt lt now.subtractDays(7)
        )
        
        return CacheStats(
            totalCaches = totalCaches.toInt(),
            freshCaches = freshCaches.toInt(),
            oldCaches = oldCaches.toInt()
        )
    }
}

data class CacheStats(
    val totalCaches: Int,
    val freshCaches: Int,
    val oldCaches: Int
)