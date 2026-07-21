package com.lvsmsmch.aichat.notification

import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.comment.database.CommentDbo
import com.lvsmsmch.aichat.notification.database.NotificationTypes
import com.lvsmsmch.aichat.notification.database.UserNotificationDbo
import com.lvsmsmch.aichat.notification.database.UserNotificationRepository
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.FcmSender
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Единая точка создания событийных уведомлений. Все методы «выстрелил и забыл»:
 * работают в своём scope, ошибки логируются и НИКОГДА не роняют основной флоу
 * (фоллоу/коммент/лайк обязаны пройти, даже если уведомление не записалось).
 */
class NotificationService(
    private val notifications: UserNotificationRepository,
    private val userRepository: UserRepository,
    private val characterRepository: CharacterRepository,
    private val followRepository: FollowRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun fire(what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                logger.error("Notification '$what' failed: ${e.message}")
            }
        }
    }

    /** Новый подписчик: отдельное уведомление каждому событию + FCM-пуш. */
    fun onNewFollower(targetUserId: String, actorUserId: String) {
        fire("follower") {
            notifications.insert(
                UserNotificationDbo(
                    userId = targetUserId,
                    type = NotificationTypes.FOLLOWER,
                    actorUserId = actorUserId,
                )
            )
            val target = userRepository.getUserById(targetUserId) ?: return@fire
            val actor = userRepository.getUserById(actorUserId) ?: return@fire
            target.fcmToken?.let { token ->
                FcmSender.send(
                    token = token,
                    title = "New follower! 🎉",
                    body = "@${actor.username} started following you",
                )
            }
        }
    }

    /** Коммент под моим персонажем (свои комменты под своим персом не считаются). */
    fun onCharacterComment(comment: CommentDbo) {
        fire("character_comment") {
            val character = characterRepository.getCharacter(comment.characterId) ?: return@fire
            if (character.authorId == comment.authorId) return@fire
            notifications.insert(
                UserNotificationDbo(
                    userId = character.authorId,
                    type = NotificationTypes.CHARACTER_COMMENT,
                    actorUserId = comment.authorId,
                    characterId = character.id,
                    commentId = comment.id,
                )
            )
        }
    }

    /** Лайк моего коммента: стак «набрал N лайков» на непрочитанном. */
    fun onCommentLiked(comment: CommentDbo, actorUserId: String) {
        if (comment.authorId == actorUserId) return
        fire("comment_like") {
            notifications.upsertStacked(
                UserNotificationDbo(
                    userId = comment.authorId,
                    type = NotificationTypes.COMMENT_LIKE,
                    actorUserId = actorUserId,
                    characterId = comment.characterId,
                    commentId = comment.id,
                    stackKey = "clike:${comment.id}",
                )
            )
        }
    }

    /** Подписки: «X добавил персонажа» всем фолловерам автора (стак на автора). */
    fun onFolloweeNewCharacter(authorId: String, characterId: String) {
        fire("followee_character") {
            val followers = followRepository.getAllFollowerIds(authorId)
            followers.forEach { followerId ->
                notifications.upsertStacked(
                    UserNotificationDbo(
                        userId = followerId,
                        type = NotificationTypes.FOLLOWEE_CHARACTER,
                        actorUserId = authorId,
                        characterId = characterId,
                        stackKey = "fchar:$followerId:$authorId",
                    )
                )
            }
        }
    }

    /** Вехи сообщений персонажа: ровно на 1 000 / 10 000 / 100 000. */
    fun checkCharacterMilestone(characterId: String) {
        fire("milestone") {
            val character = characterRepository.getCharacter(characterId) ?: return@fire
            val total = character.totalMessages
            if (total !in MILESTONES) return@fire
            val key = "milestone:$characterId:$total"
            if (notifications.existsByStackKey(key)) return@fire
            notifications.insert(
                UserNotificationDbo(
                    userId = character.authorId,
                    type = NotificationTypes.MILESTONE,
                    characterId = characterId,
                    milestone = total,
                    stackKey = key,
                )
            )
        }
    }

    private companion object {
        val MILESTONES = setOf(1_000, 10_000, 100_000)
    }
}
