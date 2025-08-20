package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat._common.database.DeletedIdsStatsRepository
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.chat.database.ChatDbo
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageDbo
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.user.database.UserRepository

class ComplexQueryHelper(
    private val transactionHelper: TransactionHelper,
    private val userRepository: UserRepository,
    private val characterRepository: CharacterRepository,
    private val reviewRepository: ReviewRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val followRepository: FollowRepository,
    private val searchSuggestionsRepository: SearchSuggestionsRepository,
    private val reviewLikeRepository: ReviewLikeRepository,
    private val deletedIdsStatsRepository: DeletedIdsStatsRepository,
    private val characterActivityLogRepository: CharacterActivityLogRepository,
) {


    // REVIEWS


    suspend fun addReview(reviewDbo: ReviewDbo) {
        transactionHelper.withTransaction { session ->
            reviewRepository.addReview(session, reviewDbo)
            characterRepository.incrementReviewsCount(session, reviewDbo.characterId, 1)
            characterRepository.updateAvgRating(
                session = session,
                characterId = reviewDbo.characterId,
                newRating = reviewRepository.getAvgRatingForCharacter(session, reviewDbo.characterId)
            )
            characterActivityLogRepository.logActivity(
                session = session,
                activityType = ActivityType.REVIEW_ADDED,
                characterId = reviewDbo.characterId,
                userId = reviewDbo.authorId
            )
        }
    }

    suspend fun updateReview(
        reviewId: String,
        characterId: String,
        rating: Int?,
        text: String?,
        oldRating: Int,
    ): ReviewDbo {
        return transactionHelper.withTransaction { session ->
            reviewRepository.updateReview(
                session = session,
                id = reviewId,
                rating = rating,
                text = text
            )
            if (oldRating != rating) {
                characterRepository.updateAvgRating(
                    session = session,
                    characterId = characterId,
                    newRating = reviewRepository.getAvgRatingForCharacter(session, characterId)
                )
            }

            return@withTransaction reviewRepository.getReviewById(reviewId)!!
        }
    }

    suspend fun deleteReview(reviewId: String) {
        transactionHelper.withTransaction { session ->
            val review = reviewRepository.getReviewById(reviewId)!!
            val characterId = review.characterId
            reviewRepository.deleteReviewById(session, reviewId)
            deletedIdsStatsRepository.entityWasDeleted(session, EntityType.REVIEW, reviewId)
            characterRepository.incrementReviewsCount(session, characterId, -1)
            reviewLikeRepository.removeAllLikesForReview(session, reviewId)
            characterRepository.updateAvgRating(
                session = session,
                characterId = characterId,
                newRating = reviewRepository.getAvgRatingForCharacter(session, characterId)
            )
        }
    }

    suspend fun likeReview(reviewId: String, userId: String) {
        transactionHelper.withTransaction { session ->
            reviewLikeRepository.likeReview(session, userId, reviewId)
            reviewRepository.incrementLikesCount(session, reviewId, 1)
        }
    }

    suspend fun unlikeReview(reviewId: String, userId: String) {
        transactionHelper.withTransaction { session ->
            reviewLikeRepository.unlikeReview(session, userId, reviewId)
            reviewRepository.incrementLikesCount(session, reviewId, -1)
        }
    }


    // CHARACTERS


    suspend fun addCharacter(characterDbo: CharacterDbo) {
        transactionHelper.withTransaction { session ->
            characterRepository.addCharacter(session, characterDbo)
            if (characterDbo.visibility == CharacterVisibility.PUBLIC.code) {
                userRepository.incrementPublicCharacterCount(session, characterDbo.authorId, 1)
            } else {
                userRepository.incrementPrivateCharacterCount(session, characterDbo.authorId, 1)
            }
//            searchSuggestionsRepository.addCharacterName(
//                session = session,
//                originalText = characterDbo.name,
//            )
        }
    }

    suspend fun updateCharacter(
        characterId: String,
        userId: String,
        name: String?,
        description: String?,
        prompt: String?,
        initialMessage: String?,
        visibility: Int?,
        pictureUrl: String?,
        category: String?,
        tags: String?,
        oldName: String,
        oldVisibility: Int
    ): CharacterDbo {
        return transactionHelper.withTransaction { session ->
            characterRepository.updateCharacter(
                session = session,
                characterId = characterId,
                name = name,
                description = description,
                prompt = prompt,
                initialMessage = initialMessage,
                visibility = visibility,
                pictureUrl = pictureUrl,
                category = category?.let { CharacterCategory.getByCode(it) },
                tags = tags?.let { CharacterTag.fromString(tags) }
            )

//            if (name != null && oldName != name) {
//                searchSuggestionsRepository.updateCharacterName(
//                    session = session,
//                    newText = name,
//                    oldText = oldName,
//                )
//            }

            if (visibility != null && oldVisibility != visibility) {

                chatRepository.deleteChatsForWhoIsNotAuthor(
                    session = session,
                    characterId = characterId,
                    authorId = userId
                )

                val changedToPrivate = oldVisibility == CharacterVisibility.PUBLIC.code &&
                        visibility == CharacterVisibility.PRIVATE.code

                val publicInc = if (changedToPrivate) -1 else 1
                val privateInc = if (changedToPrivate) 1 else -1

                userRepository.incrementPublicCharacterCount(session, userId, publicInc)
                userRepository.incrementPrivateCharacterCount(session, userId, privateInc)
            }

            return@withTransaction characterRepository.getCharacter(session, characterId)!!
        }
    }


    suspend fun deleteCharacter(characterId: String) {
        transactionHelper.withTransaction { session ->
            logger.info("1")
            val character = characterRepository.getCharacter(session, characterId)!!
            val userId = character.authorId
            logger.info("2")
            characterRepository.deleteCharacter(session, characterId)
            logger.info("3")
            deletedIdsStatsRepository.entityWasDeleted(session, EntityType.CHARACTER, characterId)
            logger.info("4")
            if (character.visibility == CharacterVisibility.PUBLIC.code) {
                userRepository.incrementPublicCharacterCount(session, userId, -1)
            } else {
                userRepository.incrementPrivateCharacterCount(session, userId, -1)
            }
            logger.info("5")
            chatRepository.deleteAllChatsByCharacterId(session, characterId)
            // we do not delete messages, because we use soft delete for chat and messages

            logger.info("6")
            val reviewIds = reviewRepository.getReviewIdsByCharacterId(session, characterId)
            logger.info("7")
            reviewRepository.deleteReviewsByIds(session, reviewIds)
            logger.info("8")
            deletedIdsStatsRepository.entitiesWereDeleted(session, EntityType.REVIEW, reviewIds)
            logger.info("9")
            reviewLikeRepository.removeAllLikesForReviews(session, reviewIds)
            logger.info("10")
        }
    }


    // USERS


    suspend fun addUser(userDbo: UserDbo) {
        transactionHelper.withTransaction { session ->
            userRepository.addUser(session, userDbo)
            val userInTransaction = userRepository.getUserById(session, "testUser001")
            logger.info("User exists in transaction: ${userInTransaction != null}")
        }
    }

    suspend fun updateUser(
        userId: String,
        username: String?,
        name: String?,
        bio: String?,
        profilePictureUrl: String?,
    ) {
        transactionHelper.withTransaction { session ->
            userRepository.updateUser(
                session = session,
                userId = userId,
                username = username,
                name = name,
                bio = bio,
                profilePictureUrl = profilePictureUrl,
            )
        }
    }

    suspend fun followUser(currentUserId: String, targetUserId: String) {
        transactionHelper.withTransaction { session ->
            followRepository.addConnection(session, followerId = currentUserId, followeeId = targetUserId)
            userRepository.incrementFollowingCount(session, currentUserId, 1)
            userRepository.incrementFollowerCount(session, targetUserId, 1)
        }
    }

    suspend fun unfollowUser(currentUserId: String, targetUserId: String) {
        transactionHelper.withTransaction { session ->
            followRepository.removeConnection(session, followerId = currentUserId, followeeId = targetUserId)
            userRepository.incrementFollowingCount(session, currentUserId, -1)
            userRepository.incrementFollowerCount(session, targetUserId, -1)
        }
    }

    suspend fun deleteUser(userId: String) {
        transactionHelper.withTransaction { session ->
            deletedIdsStatsRepository.entityWasDeleted(session, EntityType.USER, userId)

            val followerIds = followRepository.getAllFollowerIds(session, userId)
            val followingIds = followRepository.getAllFollowingIds(session, userId)
            userRepository.incrementFollowerCountForUsers(session, followingIds, -1)
            userRepository.incrementFollowingCountForUsers(session, followerIds, -1)
            followRepository.removeAllConnectionsContainingUserId(session, userId)

            val characterIds = characterRepository.getCharactersByUserId(session, userId).map { it.id }
            characterRepository.deleteCharactersByIds(session, characterIds)
            deletedIdsStatsRepository.entitiesWereDeleted(session, EntityType.CHARACTER, characterIds)

            chatRepository.deleteAllChatsByCharacterIds(session, characterIds)
            // we do not delete messages, because we use soft delete for chat and messages

            val reviewForCharactersIds = reviewRepository.getReviewIdsByCharacterIds(session, characterIds)
            val reviewByUserIds = reviewRepository.getReviewIdsByUserId(session, userId)

            val allReviewIds = (reviewForCharactersIds + reviewByUserIds).distinct()
            if (allReviewIds.isNotEmpty()) {
                reviewRepository.deleteReviewsByIds(session, allReviewIds)
                deletedIdsStatsRepository.entitiesWereDeleted(session, EntityType.REVIEW, allReviewIds)
                reviewLikeRepository.removeAllLikesForReviews(session, allReviewIds)
            }

            userRepository.deleteUser(session, userId = userId)
        }
    }


    // CHATS & MESSAGES


    suspend fun addChat(chatDbo: ChatDbo) {
        transactionHelper.withTransaction { session ->
            chatRepository.insertChat(session, chatDbo)
            chatDbo.characterIds.forEach { characterId ->

                if (chatDbo.isFirstChatWithThisCharacter) {
                    characterRepository.incrementChatsCount(session, characterId, 1)
                }

                characterActivityLogRepository.logActivity(
                    session = session,
                    activityType = ActivityType.CHAT_CREATED,
                    characterId = characterId,
                    userId = chatDbo.userId
                )
            }
        }
    }

    suspend fun addMessage(messageDbo: MessageDbo) {
        transactionHelper.withTransaction { session ->
            messageRepository.insertMessage(session, messageDbo)
            if (!messageDbo.isSentByUser) {
                val chat = chatRepository.getChatById(session, messageDbo.chatId)!!
                characterRepository.incrementMessagesCount(session, messageDbo.senderId, 1)
                characterActivityLogRepository.logActivity(
                    session = session,
                    activityType = ActivityType.MESSAGE_SENT,
                    characterId = messageDbo.senderId,
                    userId = chat.userId
                )
            }
        }
    }
}