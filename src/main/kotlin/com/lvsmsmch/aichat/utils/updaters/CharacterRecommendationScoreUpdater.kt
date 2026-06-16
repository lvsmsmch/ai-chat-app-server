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
    updateIntervalMinutes: Long = 12 * 60
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
                
                delay(TimeUnit.MINUTES.toMillis(updateIntervalMinutes))
                
            } catch (e: CancellationException) {
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
    
    val rating = if (character.averageRating == 0f) 3f else character.averageRating
    score += (rating / 5f) * 0.1f
    score += min(character.totalReviews / 100f, 1f) * 0.1f
    score += min(character.totalChats / 1000f, 1f) * 0.1f
    score += min(character.totalMessages / 10000f, 1f) * 0.2f
    
    val connectionStrength = character.coOccurrenceScore.values.sum()
    score += min(connectionStrength, 1f) * 0.2f

    score += min((character.recommendationsScoreMultiplier ?: 0f), 1f) * 0.3f
    
    score += min(character.trendingScore, 1f)
    
    return score
}