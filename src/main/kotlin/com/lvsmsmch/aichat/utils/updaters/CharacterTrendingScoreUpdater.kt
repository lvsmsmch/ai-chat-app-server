package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.character.database.ActivityType
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.math.min

fun configureCharacterTrendingScoreUpdater(
    databaseScope: CoroutineScope,
    characterRepository: CharacterRepository,
    characterActivityLogRepository: CharacterActivityLogRepository,
    updateIntervalMinutes: Long = 60, // Default to once an hour
    includeIntervalMinutes: Long = 7 * 24 * 60  // Default to a week
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                logger.info("Starting trending score calculation")
                val cutoffDate = UtcTimestamp.now().subtractMinutes(includeIntervalMinutes)
                val allCharacters = characterRepository.getAllPublicCharacters()

                logger.info("Calculating trending scores for ${allCharacters.size} characters")

                for (character in allCharacters) {
                    val uniqueUsersThatCreatedChat = characterActivityLogRepository.getUniqueUsersForActivity(
                        characterId = character.id,
                        activityType = ActivityType.CHAT_CREATED,
                        since = cutoffDate
                    )
                    val uniqueUsersThatSentMessage = characterActivityLogRepository.getUniqueUsersForActivity(
                        characterId = character.id,
                        activityType = ActivityType.MESSAGE_SENT,
                        since = cutoffDate
                    )
                    val messagesCount = characterActivityLogRepository.getActivity(
                        characterId = character.id,
                        activityType = ActivityType.MESSAGE_SENT,
                        since = cutoffDate
                    )
                    val uniqueUsersThatLeftReview = characterActivityLogRepository.getUniqueUsersForActivity(
                        characterId = character.id,
                        activityType = ActivityType.REVIEW_ADDED,
                        since = cutoffDate
                    )

                    val trendingScore = calculateTrendingScore(
                        uniqueUsersThatCreatedChat = uniqueUsersThatCreatedChat,
                        uniqueUsersThatSentMessage = uniqueUsersThatSentMessage,
                        messagesCount = messagesCount,
                        uniqueUsersThatLeftReview = uniqueUsersThatLeftReview
                    )

                    characterRepository.updateTrendingScore(
                        characterId = character.id,
                        trendingScore = trendingScore
                    )
                }
                logger.info("Trending score calculation completed successfully")

                // Wait for the next scheduled update
                delay(TimeUnit.MINUTES.toMillis(updateIntervalMinutes))
            } catch (e: CancellationException) {
                // Expected during cancellation
                logger.debug("Trending score updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during trending score calculation: ${e.message}", e)
                cancel()
            }
        }
    }

    return parentJob
}

private fun calculateTrendingScore(
    uniqueUsersThatCreatedChat: Int,
    uniqueUsersThatSentMessage: Int,
    messagesCount: Int,
    uniqueUsersThatLeftReview: Int
): Float {
    var score = 0f

    // Активность создания чатов (25%)
    score += min(uniqueUsersThatCreatedChat / 50f, 1f) * 0.25f

    // Активность пользователей в сообщениях (25%)
    score += min(uniqueUsersThatSentMessage / 100f, 1f) * 0.25f

    // Общее количество сообщений (30%)
    score += min(messagesCount / 500f, 1f) * 0.30f

    // Активность отзывов (20%)
    score += min(uniqueUsersThatLeftReview / 20f, 1f) * 0.20f

    return score
}