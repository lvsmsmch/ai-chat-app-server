package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.cache.database.CategoryRecommendationsCacheRepository
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

fun configureCategoryRecommendationsUpdater(
    databaseScope: CoroutineScope,
    characterRepository: CharacterRepository,
    categoryRecommendationsCacheRepository: CategoryRecommendationsCacheRepository,
    updateIntervalMinutes: Long
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                logger.info("Starting category base cache update")

                val categoriesToUpdate = categoryRecommendationsCacheRepository.getCategoriesNeedingUpdate(3)

                if (categoriesToUpdate.isEmpty()) {
                    logger.info("All category base caches are fresh")
                } else {
                    logger.info("Updating base cache for ${categoriesToUpdate.size} categories")

                    categoriesToUpdate.forEach { category ->
                        val categoryCharacters = characterRepository.getAllPublicCharactersForCategory(category)
                            .sortedByDescending { (it.trendingScore * 0.4f) + (it.recommendationScore * 0.6f) }
                            .take(5000)
                            .map { it.id }
                        categoryRecommendationsCacheRepository.upsertCategoryCache(category, categoryCharacters)
                        logger.info("Updated base cache for category ${category.code}: ${categoryCharacters.size} characters")
                    }
                }

                logger.info("Category base cache update completed")
                delay(TimeUnit.MINUTES.toMillis(updateIntervalMinutes))

            } catch (e: CancellationException) {
                logger.debug("Category base cache updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during category base cache update: ${e.message}", e)
                delay(TimeUnit.HOURS.toMillis(1))
            }
        }
    }

    return parentJob
}