package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.math.min

fun configureCharacterRecommendationScoreUpdater(
    databaseScope: CoroutineScope,
    characterRepository: CharacterRepository,
    updateIntervalMinutes: Long = 12 * 60 // раз в 12 часов
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                logger.info("Starting recommendation score calculation")
                val allCharacters = characterRepository.getAllPublicCharacters()

                logger.info("Calculating recommendation scores for ${allCharacters.size} characters")

                allCharacters.forEach { character ->
                    val recommendationScore = calculateGlobalRecommendationScore(character)
                    
                    characterRepository.updateRecommendationScore(
                        characterId = character.id,
                        recommendationScore = recommendationScore
                    )
                }
                
                logger.info("Recommendation score calculation completed for ${allCharacters.size} characters")
                
                // Wait for the next scheduled update
                delay(TimeUnit.MINUTES.toMillis(updateIntervalMinutes))
                
            } catch (e: CancellationException) {
                // Expected during cancellation
                logger.debug("Recommendation score updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during recommendation score calculation: ${e.message}", e)
                cancel()
            }
        }
    }

    return parentJob
}

private fun calculateGlobalRecommendationScore(character: CharacterDbo): Float {
    var score = 0f
    
    // Базовые метрики качества (60%)
    val rating = if (character.averageRating == 0f) 3f else character.averageRating
    score += (rating / 5f) * 0.1f // рейтинг (0-5) -> (0-1)
    score += min(character.totalReviews / 100f, 1f) * 0.1f // количество отзывов
    score += min(character.totalChats / 1000f, 1f) * 0.1f // популярность чатов
    score += min(character.totalMessages / 10000f, 1f) * 0.2f // активность общения
    
    // Связанность с другими персонажами (30%)
    val connectionStrength = character.coOccurrenceScore.values.sum()
    score += min(connectionStrength, 1f) * 0.2f

    score += min((character.recommendationsScoreMultiplier ?: 0f), 1f) * 0.3f
    
    // Трендинг компонент
    score += min(character.trendingScore, 1f)
    
    return score
}