package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat._common.database.DeletedIdsStatsRepository
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.chat.database.ChatDbo
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageDbo
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.comment.database.CommentDbo
import com.lvsmsmch.aichat.comment.database.CommentLikeRepository
import com.lvsmsmch.aichat.comment.database.CommentRepository
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
    private val sessionRepository: com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository,
    private val userNotificationRepository: com.lvsmsmch.aichat.notification.database.UserNotificationRepository,
    private val deviceLimitCarryoverRepository: com.lvsmsmch.aichat.user.database.DeviceLimitCarryoverRepository,
    private val followRepository: FollowRepository,
    private val searchSuggestionsRepository: SearchSuggestionsRepository,
    private val reviewLikeRepository: ReviewLikeRepository,
    private val characterLikeRepository: com.lvsmsmch.aichat.character.database.CharacterLikeRepository,
    private val commentRepository: CommentRepository,
    private val commentLikeRepository: CommentLikeRepository,
    private val deletedIdsStatsRepository: DeletedIdsStatsRepository,
    private val characterActivityLogRepository: CharacterActivityLogRepository,
    private val notificationService: com.lvsmsmch.aichat.notification.NotificationService,
) {




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




    suspend fun addChat(chatDbo: ChatDbo) {
        transactionHelper.withTransaction { session ->
            chatRepository.insertChat(session, chatDbo)
            userRepository.notifyChatWasCreated(session, chatDbo.userId)
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
            // Изображения НЕ тратят лимит сообщений — у них свой дневной счётчик
            if (!messageDbo.isSentByUser && !messageDbo.isImage) {
                val chat = chatRepository.getChatById(session, messageDbo.chatId)!!
                userRepository.notifyCharacterMessageWasSent(session, chat.userId)
                characterRepository.incrementMessagesCount(session, messageDbo.senderId, 1)
                characterActivityLogRepository.logActivity(
                    session = session,
                    activityType = ActivityType.MESSAGE_SENT,
                    characterId = messageDbo.senderId,
                    userId = chat.userId
                )
            }
        }
        // Вне транзакции: веха 1к/10к/100к сообщений персонажа
        if (!messageDbo.isSentByUser && !messageDbo.isImage) {
            notificationService.checkCharacterMilestone(messageDbo.senderId)
        }
    }
}
