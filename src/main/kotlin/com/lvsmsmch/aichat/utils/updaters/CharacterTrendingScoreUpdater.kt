package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.character.database.ActivityType
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.utils.UtcTimestamp
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

fun configureCharacterTrendingScoreUpdater(
    logger: Logger,
    databaseScope: CoroutineScope,
    characterRepository: CharacterRepository,
    characterActivityLogRepository: CharacterActivityLogRepository,
    updateIntervalMinutes: Long = 5, // Default to once a day
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

                    val trendingScore = (uniqueUsersThatCreatedChat * 0.005f) +
                            (uniqueUsersThatSentMessage * 0.01f) +
                            (messagesCount * 0.001f) +
                            (uniqueUsersThatLeftReview * 0.03f)

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