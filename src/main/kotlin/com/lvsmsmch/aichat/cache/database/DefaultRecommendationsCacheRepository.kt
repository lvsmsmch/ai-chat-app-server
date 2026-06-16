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
data class DefaultRecommendationsCacheDbo(
    @BsonId val id: String = "default_recommendations_cache",
    val characterIds: List<String>,
    val updatedAt: String = UtcTimestamp.now().toString(),
    val version: String = ObjectId().toString()
)

class DefaultRecommendationsCacheRepository(
    private val collection: CoroutineCollection<DefaultRecommendationsCacheDbo>
) {

    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    suspend fun updateDefaultCache(characterIds: List<String>) {
        val cache = DefaultRecommendationsCacheDbo(
            characterIds = characterIds,
            updatedAt = UtcTimestamp.now().toString()
        )
        
        collection.replaceOneById("default_recommendations_cache", cache, ReplaceOptions().upsert(true))
    }

    suspend fun getDefaultRecommendations(): List<String> {
        val cache = collection.findOneById("default_recommendations_cache")
        return cache?.characterIds ?: emptyList()
    }

    suspend fun getDefaultCache(): DefaultRecommendationsCacheDbo? {
        return collection.findOneById("default_recommendations_cache")
    }

    suspend fun hasRecentCache(maxAgeHours: Long = 1): Boolean {
        val cache = collection.findOneById("default_recommendations_cache") ?: return false
        val cutoff = UtcTimestamp.now().subtractHours(maxAgeHours)
        return UtcTimestamp.parse(cache.updatedAt).isAfter(cutoff)
    }
}