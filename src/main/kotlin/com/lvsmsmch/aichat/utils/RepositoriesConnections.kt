package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat._common.database.EntityIdStatsRepository
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.cache.database.DefaultRecommendationsCacheRepository
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import io.ktor.util.logging.*
import kotlinx.coroutines.*

/**
 * Configures connections between repositories to handle database change events.
 * Returns a Job that can be cancelled to stop all repository connections.
 */
fun configureRepositoriesConnections(
    logger: Logger,
    databaseScope: CoroutineScope,
    entityIdStatsRepository: EntityIdStatsRepository,
    userRepository: UserRepository,
    followRepository: FollowRepository,
    characterRepository: CharacterRepository,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    reviewRepository: ReviewRepository,
    searchSuggestionsRepository: SearchSuggestionsRepository,
    reviewLikeRepository: ReviewLikeRepository,
): Job {
    // Create a parent job for all repository connections
    val parentJob = SupervisorJob()
    val connectionScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    suspend fun updateReviewsAvgRating(characterId: String) {
        characterRepository.updateAvgRating(
            characterId = characterId,
            newRating = reviewRepository.getAvgRatingForCharacter(characterId)
        )
    }

    // User repository connections
    connectionScope.launch {
        try {
            userRepository.databaseEventsFlow
                .collect { event ->
                    ensureActive() // Check for cancellation
                    try {
                        when (event) {
                            is DatabaseEvent.Created -> {
                            }

                            is DatabaseEvent.Updated -> { /* Handle update if needed */
                            }

                            is DatabaseEvent.Deleted -> {
                                entityIdStatsRepository.idWasDeleted(EntityType.USER, event.old.id)
                                characterRepository.deleteAllCharacterByUserId(event.old.id)
                                followRepository.removeAllConnectionsContainingUserId(event.old.id)
                                reviewRepository.deleteAllReviewsByUserId(event.old.id)
                                reviewLikeRepository.removeAllLikesByUserId(event.old.id)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error processing user event: ${e.message}", e)
                    }
                }
        } catch (e: CancellationException) {
            // Expected during cancellation - log at debug level
            logger.debug("User repository flow collection cancelled")
        } catch (e: Exception) {
            logger.error("Unexpected error in user repository flow: ${e.message}", e)
        }
    }

    connectionScope.launch {
        try {
            followRepository.databaseEventsFlow
                .collect { event ->
                    ensureActive() // Check for cancellation
                    try {
                        when (event) {
                            is DatabaseEvent.Created -> {
                                val followerId = event.new.followerId
                                val followeeId = event.new.followeeId

                                // ✅ ИНКРЕМЕНТАЛЬНОЕ обновление вместо пересчета
                                userRepository.incrementFollowingCount(followerId, 1)
                                userRepository.incrementFollowerCount(followeeId, 1)
                            }

                            is DatabaseEvent.Updated -> {
                                // Обычно follow записи не обновляются, только создаются/удаляются
                                // Но если нужно, можно добавить логику
                            }

                            is DatabaseEvent.Deleted -> {
                                val followerId = event.old.followerId
                                val followeeId = event.old.followeeId

                                // ✅ ДЕКРЕМЕНТАЛЬНОЕ обновление при удалении
                                userRepository.incrementFollowingCount(followerId, -1)
                                userRepository.incrementFollowerCount(followeeId, -1)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error processing follow event: ${e.message}", e)
                    }
                }
        } catch (e: CancellationException) {
            // Expected during cancellation - log at debug level
            logger.debug("Follow repository flow collection cancelled")
        } catch (e: Exception) {
            logger.error("Unexpected error in follow repository flow: ${e.message}", e)
        }
    }

    connectionScope.launch {
        try {
            characterRepository.databaseEventsFlow
                .collect { event ->
                    ensureActive()
                    try {
                        when (event) {
                            is DatabaseEvent.Created -> {
                                val userId = event.new.authorId

                                if (event.new.visibility == CharacterVisibility.PUBLIC.code) {
                                    userRepository.incrementPublicCharacterCount(userId, 1)
                                } else {
                                    userRepository.incrementPrivateCharacterCount(userId, 1)
                                }

                                searchSuggestionsRepository.addSuggestion(
                                    originalText = event.new.name,
                                    isCharacterName = true
                                )
                            }

                            is DatabaseEvent.Updated -> {
                                val userId = event.new.authorId
                                val oldVisibility = event.old.visibility
                                val newVisibility = event.new.visibility

                                // Обработка изменения видимости
                                if (oldVisibility != newVisibility) {
                                    chatRepository.updateChatsAfterCharacterVisibilityWasChanged(event.new)

                                    // ✅ Инкрементальное обновление счетчиков
                                    when {
                                        oldVisibility == CharacterVisibility.PUBLIC.code &&
                                                newVisibility == CharacterVisibility.PRIVATE.code -> {
                                            userRepository.incrementPublicCharacterCount(userId, -1)
                                            userRepository.incrementPrivateCharacterCount(userId, 1)
                                        }
                                        oldVisibility == CharacterVisibility.PRIVATE.code &&
                                                newVisibility == CharacterVisibility.PUBLIC.code -> {
                                            userRepository.incrementPrivateCharacterCount(userId, -1)
                                            userRepository.incrementPublicCharacterCount(userId, 1)
                                        }
                                    }
                                }
                            }

                            is DatabaseEvent.Deleted -> {
                                val userId = event.old.authorId

                                entityIdStatsRepository.idWasDeleted(EntityType.CHARACTER, event.old.id)
                                chatRepository.deleteAllChatsByCharacterId(event.old.id)
                                reviewRepository.deleteAllReviewsByCharacterId(event.old.id)

                                if (event.old.visibility == CharacterVisibility.PUBLIC.code) {
                                    userRepository.incrementPublicCharacterCount(userId, -1)
                                } else {
                                    userRepository.incrementPrivateCharacterCount(userId, -1)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error processing character event: ${e.message}", e)
                    }
                }
        } catch (e: CancellationException) {
            logger.debug("Character repository flow collection cancelled")
        } catch (e: Exception) {
            logger.error("Unexpected error in character repository flow: ${e.message}", e)
        }
    }

    // Chat repository connections
    connectionScope.launch {
        try {
            chatRepository.databaseEventsFlow
                .collect { event ->
                    ensureActive() // Check for cancellation
                    try {
                        when (event) {
                            is DatabaseEvent.Created -> {
                                event.new.characterIds.forEach { characterId ->
                                    val isFirstChatWithThisCharacter = chatRepository.findChatByUserAndCharacter(
                                        userId = event.new.userId,
                                        characterId = characterId,
                                        includeDeleted = true
                                    ) == null
                                    if (isFirstChatWithThisCharacter) {
                                        characterRepository.incrementChatsCount(characterId, 1)
                                    }
                                }
                            }

                            is DatabaseEvent.Updated -> { /* Handle update if needed */
                            }

                            is DatabaseEvent.Deleted -> {
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error processing chat event: ${e.message}", e)
                    }
                }
        } catch (e: CancellationException) {
            logger.debug("Chat repository flow collection cancelled")
        } catch (e: Exception) {
            logger.error("Unexpected error in chat repository flow: ${e.message}", e)
        }
    }

    // Message repository connections
    connectionScope.launch {
        try {
            messageRepository.databaseEventsFlow
                .collect { event ->
                    ensureActive() // Check for cancellation
                    try {
                        when (event) {
                            is DatabaseEvent.Created -> {
                                val senderId = event.new.senderId
                                val isCharacterMessage = characterRepository.getCharacter(senderId) != null
                                if (isCharacterMessage) {
                                    characterRepository.incrementMessagesCount(senderId, 1)
                                }
                            }

                            is DatabaseEvent.Updated -> { /* Handle update if needed */
                            }

                            is DatabaseEvent.Deleted -> { /* Handle deletion if needed */
                            }
                        }

                        // Update chat with latest message info
//                        chatRepository.updateLastMessageInChat(
//                            chatId = chatId,
//                            message = messageRepository.getLastMessageInChat(chatId)
//                        )
//                        chatRepository.updateUnreadMessagesCountInChat(
//                            chatId = chatId,
//                            unreadMessageCount = messageRepository.countUnreadMessagesInChat(chatId)
//                        )
                    } catch (e: Exception) {
                        logger.error("Error processing message event: ${e.message}", e)
                    }
                }
        } catch (e: CancellationException) {
            logger.debug("Message repository flow collection cancelled")
        } catch (e: Exception) {
            logger.error("Unexpected error in message repository flow: ${e.message}", e)
        }
    }


    connectionScope.launch {
        try {
            reviewRepository.databaseEventsFlow
                .collect { event ->
                    ensureActive() // Check for cancellation
                    try {
                        val characterId = event.latestObject.characterId

                        when (event) {
                            is DatabaseEvent.Created -> {
                                characterRepository.incrementReviewsCount(characterId, 1)
                                updateReviewsAvgRating(characterId)
                            }

                            is DatabaseEvent.Updated -> {
                                if (event.old.rating != event.new.rating) {
                                    updateReviewsAvgRating(characterId)
                                }
                            }

                            is DatabaseEvent.Deleted -> {
                                entityIdStatsRepository.idWasDeleted(EntityType.REVIEW, event.old.id)
                                characterRepository.incrementReviewsCount(characterId, -1)
                                reviewLikeRepository.removeAllLikesForReview(event.old.id)
                                updateReviewsAvgRating(characterId)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error processing review event: ${e.message}", e)
                    }
                }
        } catch (e: CancellationException) {
            logger.debug("Review repository flow collection cancelled")
        } catch (e: Exception) {
            logger.error("Unexpected error in review repository flow: ${e.message}", e)
        }
    }

    connectionScope.launch {
        try {
            reviewLikeRepository.databaseEventsFlow
                .collect { event ->
                    ensureActive() // Check for cancellation
                    try {
                        when (event) {
                            is DatabaseEvent.Created -> {
                                val reviewId = event.new.reviewId
                                reviewRepository.incrementLikesCount(reviewId, 1)
                            }

                            is DatabaseEvent.Updated -> {
                            }

                            is DatabaseEvent.Deleted -> {
                                val reviewId = event.old.reviewId
                                reviewRepository.incrementLikesCount(reviewId, -1)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error processing review event: ${e.message}", e)
                    }
                }
        } catch (e: CancellationException) {
            logger.debug("Review repository flow collection cancelled")
        } catch (e: Exception) {
            logger.error("Unexpected error in review repository flow: ${e.message}", e)
        }
    }

    // Return the parent job so it can be cancelled when needed
    return parentJob
}