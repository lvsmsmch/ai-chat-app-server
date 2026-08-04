package com.lvsmsmch.aichat.cache.database

import com.lvsmsmch.aichat.character.database.CharacterCategory
import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toCategoryRecommendationsCacheDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

@Serializable
data class CategoryRecommendationsCacheDbo(
    @BsonId val categoryCode: String,
    val characterIds: List<String>,
    val updatedAt: String = UtcTimestamp.now().toString(),
    val version: String = ObjectId().toString()
)

class CategoryRecommendationsCacheRepository {

    suspend fun upsertCategoryCache(category: CharacterCategory, characterIds: List<String>) {
        val cache = CategoryRecommendationsCacheDbo(
            categoryCode = category.code,
            characterIds = characterIds,
        )
        dbQuery { Tables.CategoryRecommendationsCache.upsert { it.from(cache) } }
    }

    suspend fun getCategoryCache(category: CharacterCategory): CategoryRecommendationsCacheDbo? =
        dbQuery {
            Tables.CategoryRecommendationsCache.selectAll()
                .where { Tables.CategoryRecommendationsCache.categoryCode eq category.code }
                .limit(1)
                .firstOrNull()
                ?.toCategoryRecommendationsCacheDbo()
        }

    /**
     * Пересчитываем ВСЕ категории, а не только протухшие: расчёт дешёвый, а
     * пропущенная категория висела бы со старым списком до следующего цикла.
     */
    suspend fun getCategoriesNeedingUpdate(ttlHours: Long = 3): List<CharacterCategory> =
        CharacterCategory.entries.toList()

    suspend fun deleteAllCacheForCategory(category: CharacterCategory) {
        dbQuery {
            Tables.CategoryRecommendationsCache.deleteWhere {
                Tables.CategoryRecommendationsCache.categoryCode eq category.code
            }
        }
    }

    suspend fun getCacheStats(): CategoryCacheStats = dbQuery {
        val now = UtcTimestamp.now()
        val total = Tables.CategoryRecommendationsCache.selectAll().count()
        val fresh = Tables.CategoryRecommendationsCache.selectAll()
            .where {
                Tables.CategoryRecommendationsCache.updatedAt greaterEq
                    now.subtractHours(3).toString()
            }
            .count()
        val present = Tables.CategoryRecommendationsCache.selectAll()
            .map { it[Tables.CategoryRecommendationsCache.categoryCode] }
            .toSet()
        CategoryCacheStats(
            totalCaches = total.toInt(),
            freshCaches = fresh.toInt(),
            cachesByCategory = CharacterCategory.entries.associate { category ->
                category.code to if (category.code in present) 1 else 0
            },
        )
    }
}

data class CategoryCacheStats(
    val totalCaches: Int,
    val freshCaches: Int,
    val cachesByCategory: Map<String, Int>
)
