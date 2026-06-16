package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.cache.database.DefaultRecommendationsCacheRepository
import com.lvsmsmch.aichat.utils.getRecommendations
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

fun configureDefaultRecommendationsUpdater(
    databaseScope: CoroutineScope,
    characterRepository: CharacterRepository,
    defaultRecommendationsCacheRepository: DefaultRecommendationsCacheRepository,
    updateIntervalMinutes: Long = 60
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                logger.info("Starting default base cache update")

                val topCharacters = characterRepository.getAllPublicCharacters().getRecommendations()

                defaultRecommendationsCacheRepository.updateDefaultCache(topCharacters)
                logger.info("Updated default base cache with ${topCharacters.size} characters")

                delay(TimeUnit.MINUTES.toMillis(updateIntervalMinutes))

            } catch (e: CancellationException) {
                logger.debug("Default base cache updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during default base cache update: ${e.message}", e)
                delay(TimeUnit.HOURS.toMillis(1))
            }
        }
    }

    return parentJob
}