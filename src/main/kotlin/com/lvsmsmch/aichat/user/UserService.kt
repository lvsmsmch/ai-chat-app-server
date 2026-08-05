package com.lvsmsmch.aichat.user

import com.lvsmsmch.aichat._common.database.DeletedIdsStatsRepository
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.CharacterLikeRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.comment.database.CommentLikeRepository
import com.lvsmsmch.aichat.comment.database.CommentRepository
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.database.DeviceLimitCarryoverRepository
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.TransactionHelper

/**
 * Аккаунт: создание, правка профиля, подписки и удаление со всем каскадом.
 *
 * Удаление — самая тяжёлая операция во всём проекте: за юзером уходят его
 * персонажи, чаты, сообщения, отзывы, комменты, лайки и подписки, и всё это
 * обязано произойти одной транзакцией.
 */
class UserService(
    private val userRepository: UserRepository,
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val reviewRepository: ReviewRepository,
    private val reviewLikeRepository: ReviewLikeRepository,
    private val commentRepository: CommentRepository,
    private val commentLikeRepository: CommentLikeRepository,
    private val characterLikeRepository: CharacterLikeRepository,
    private val followRepository: FollowRepository,
    private val userBlockRepository: com.lvsmsmch.aichat.user.database.UserBlockRepository,
    private val sessionRepository: SessionRepository,
    private val userNotificationRepository: com.lvsmsmch.aichat.notification.database.UserNotificationRepository,
    private val deviceLimitCarryoverRepository: DeviceLimitCarryoverRepository,
    private val deletedIdsStatsRepository: DeletedIdsStatsRepository,
    private val transactionHelper: TransactionHelper,
) {

    suspend fun addUser(userDbo: UserDbo) {
        transactionHelper.withTransaction { session ->
            userRepository.addUser(session, userDbo)
        }
    }

    suspend fun updateUser(
        userId: String,
        username: String?,
        name: String?,
        bio: String?,
        profilePictureUrl: String?,
        profilePictureUrlThumbnail: String?,
        removePicture: Boolean?,
        color: String? = null,
    ) {
        transactionHelper.withTransaction { session ->
            userRepository.updateUser(
                session = session,
                userId = userId,
                username = username,
                name = name,
                bio = bio,
                profilePictureUrl = profilePictureUrl,
                profilePictureUrlThumbnail = profilePictureUrlThumbnail,
                removePicture = removePicture,
                color = color,
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
        // Анти-абьюз: потраченные лимиты записываются на deviceId — новый гость
        // на этом устройстве их унаследует, «удалил-пересоздал» не помогает
        userRepository.getUserById(userId)?.let {
            runCatching { deviceLimitCarryoverRepository.save(it) }
        }
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

            val reviewForCharactersIds = reviewRepository.getReviewIdsByCharacterIds(session, characterIds)
            val reviewByUserIds = reviewRepository.getReviewIdsByUserId(session, userId)

            val allReviewIds = (reviewForCharactersIds + reviewByUserIds).distinct()
            if (allReviewIds.isNotEmpty()) {
                reviewRepository.deleteReviewsByIds(session, allReviewIds)
                deletedIdsStatsRepository.entitiesWereDeleted(session, EntityType.REVIEW, allReviewIds)
                reviewLikeRepository.removeAllLikesForReviews(session, allReviewIds)
            }

            // Комменты: к персонажам юзера — целиком; его собственные — вместе с
            // ветками под его корневыми; счётчики выживших чужих веток/персонажей чиним
            val commentsForCharacters = commentRepository.getCommentIdsByCharacterIds(session, characterIds)
            val ownComments = commentRepository.getCommentsByUserId(session, userId)
            val ownRootIds = ownComments.filter { it.parentId == null }.map { it.id }
            val underOwnRoots = commentRepository.getRepliesByParentIds(session, ownRootIds)
            val allCommentIds = (commentsForCharacters + ownComments.map { it.id } + underOwnRoots.map { it.id }).distinct()
            if (allCommentIds.isNotEmpty()) {
                commentRepository.deleteCommentsByIds(session, allCommentIds)
                deletedIdsStatsRepository.entitiesWereDeleted(session, EntityType.COMMENT, allCommentIds)
                commentLikeRepository.removeAllLikesForComments(session, allCommentIds)
                // Ответы юзера под ЧУЖИМИ корнями — минус в repliesCount этих корней
                ownComments.filter { it.parentId != null && it.parentId !in ownRootIds }
                    .groupBy { it.parentId!! }
                    .forEach { (parentId, list) ->
                        commentRepository.incrementRepliesCount(session, parentId, -list.size)
                    }
                // totalComments у чужих персонажей
                (ownComments + underOwnRoots).distinctBy { it.id }
                    .filter { it.characterId !in characterIds }
                    .groupBy { it.characterId }
                    .forEach { (charId, list) ->
                        characterRepository.incrementCommentsCount(session, charId, -list.size)
                    }
            }

            // Лайки, поставленные юзером: снимаем и чиним счётчики персонажей
            val userLikes = characterLikeRepository.getLikesByUser(session, userId)
            characterLikeRepository.removeAllByUser(session, userId)
            userLikes.map { it.characterId }.filter { it !in characterIds }
                .groupingBy { it }.eachCount()
                .forEach { (charId, n) -> characterRepository.incrementLikesCount(charId, -n) }
            // Лайки на удалённых персонажах юзера — просто убираем
            characterLikeRepository.removeAllForCharacters(session, characterIds)

            // Собственные чаты юзера и ВСЕ их сообщения (раньше оставались навсегда)
            val ownChatIds = chatRepository.deleteAllChatsByUserId(session, userId)
            messageRepository.deleteAllMessagesInChats(session, ownChatIds)

            userRepository.deleteUser(session, userId = userId)
        }
        // Вне транзакции (не критично к атомарности): сессии, уведомления и
        // блокировки — и его собственные, и чужие на него
        sessionRepository.deleteAllByUserId(userId)
        userNotificationRepository.deleteAllForUser(userId)
        runCatching { userBlockRepository.removeAllContaining(userId) }
    }
}
