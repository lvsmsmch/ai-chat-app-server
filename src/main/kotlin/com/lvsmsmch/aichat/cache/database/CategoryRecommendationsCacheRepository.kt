package com.lvsmsmch.aichat.cache.database

import com.lvsmsmch.aichat.character.database.CharacterCategory
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.mongodb.client.model.ReplaceOptions
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class CategoryRecommendationsCacheDbo(
    @BsonId val categoryCode: String,
    val characterIds: List<String>,
    val updatedAt: UtcTimestamp = UtcTimestamp.now(),
    val version: String = ObjectId().toString()
)

class CategoryRecommendationsCacheRepository(
    private val collection: CoroutineCollection<CategoryRecommendationsCacheDbo>
) {

    /**
     * FLOW
     */
    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    /**
     * CREATE / UPDATE
     */
    suspend fun upsertCategoryCache(category: CharacterCategory, characterIds: List<String>) {
        val cache = CategoryRecommendationsCacheDbo(
            categoryCode = category.code,
            characterIds = characterIds
        )
        
        collection.replaceOneById(category.code, cache, ReplaceOptions().upsert(true))
    }

    /**
     * READ
     */
    suspend fun getCategoryCache(category: CharacterCategory): CategoryRecommendationsCacheDbo? {
        return collection.findOneById(category.code)
    }

    suspend fun getCachedCharacters(category: CharacterCategory): List<String> {
        return getCategoryCache(category)?.characterIds ?: emptyList()
    }

    suspend fun hasFreshCache(category: CharacterCategory, ttlHours: Long = 3): Boolean {
        val cache = getCategoryCache(category) ?: return false
        val expirationTime = cache.updatedAt.addHours(ttlHours)
        return expirationTime.isAfter(UtcTimestamp.now())
    }

    suspend fun getCategoriesNeedingUpdate(ttlHours: Long = 3): List<CharacterCategory> {
        val now = UtcTimestamp.now()
        val cutoff = now.subtractHours(ttlHours)
        
        val needingUpdate = mutableListOf<CharacterCategory>()
        
        // Проверяем все комбинации категорий и сидов
        CharacterCategory.entries.forEach { category ->
                val cache = getCategoryCache(category)
                if (cache == null || cache.updatedAt.isBefore(cutoff)) {
                    needingUpdate.add(category)
            }
        }

        return needingUpdate
    }

    /**
     * DELETE
     */

    suspend fun deleteAllCacheForCategory(category: CharacterCategory) {
        collection.deleteMany(
            CategoryRecommendationsCacheDbo::categoryCode eq category.code
        )
    }

    /**
     * UTILITY
     */
    suspend fun getCacheStats(): CategoryCacheStats {
        val totalCaches = collection.countDocuments()
        val now = UtcTimestamp.now()
        
        val freshCaches = collection.countDocuments(
            CategoryRecommendationsCacheDbo::updatedAt gte now.subtractHours(3)
        )
        
        val cachesByCategory = mutableMapOf<String, Int>()
        CharacterCategory.entries.forEach { category ->
            val count = collection.countDocuments(
                CategoryRecommendationsCacheDbo::categoryCode eq category.code
            )
            cachesByCategory[category.code] = count.toInt()
        }
        
        return CategoryCacheStats(
            totalCaches = totalCaches.toInt(),
            freshCaches = freshCaches.toInt(),
            cachesByCategory = cachesByCategory
        )
    }
}

data class CategoryCacheStats(
    val totalCaches: Int,
    val freshCaches: Int,
    val cachesByCategory: Map<String, Int>
)