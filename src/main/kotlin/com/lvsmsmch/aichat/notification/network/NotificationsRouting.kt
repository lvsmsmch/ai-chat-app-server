package com.lvsmsmch.aichat.notification.network

import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.comment.database.CommentRepository
import com.lvsmsmch.aichat.notification.database.UserNotificationRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

/** Событийные уведомления: лента, прочитанность, бейдж непрочитанных. */
fun Route.configureNotificationsRouting(
    sessionRepository: SessionRepository,
    userNotificationRepository: UserNotificationRepository,
    userRepository: UserRepository,
    characterRepository: CharacterRepository,
    commentRepository: CommentRepository,
) {
    route("/notifications") {

        /** Лента: новые сверху, курсор — updatedAt последнего элемента. */
        get("/feed") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val cursor = call.request.queryParameters["cursor"]
            val size = (call.request.queryParameters["size"]?.toIntOrNull() ?: 30).coerceIn(1, 100)

            val dbos = userNotificationRepository.list(sessionDbo.userId, cursor, size)
            val items = dbos.map { n ->
                val actor = n.actorUserId?.let { userRepository.getUserById(it) }
                val character = n.characterId?.let { characterRepository.getCharacter(it) }
                val comment = n.commentId?.let { commentRepository.getCommentById(it) }
                NotificationItemDto(
                    id = n.id,
                    type = n.type,
                    at = n.updatedAt,
                    isRead = n.isRead,
                    count = n.count,
                    milestone = n.milestone,
                    actor = actor?.let {
                        NotificationActorDto(
                            id = it.id,
                            username = it.username,
                            picUrl = it.profilePictureUrlThumbnail ?: it.profilePictureUrl,
                            color = it.color,
                        )
                    },
                    character = character?.let {
                        NotificationCharacterDto(id = it.id, name = it.name, picUrl = it.picUrlThumbnail ?: it.picUrl)
                    },
                    commentText = comment?.text?.take(80),
                    commentId = comment?.let { it.parentId ?: it.id },
                )
            }
            call.respondSuccess(
                NotificationFeedResponse(
                    items = items,
                    nextCursor = if (dbos.size == size) dbos.last().updatedAt else null,
                    unreadCount = userNotificationRepository.unreadCount(sessionDbo.userId),
                )
            )
        }

        /** Бейдж на нижнем таббаре. */
        get("/unread-count") {
            val sessionDbo = sessionRepository.verifyToken(call)
            call.respondSuccess(UnreadCountResponse(userNotificationRepository.unreadCount(sessionDbo.userId)))
        }

        /** Прочитанность конкретных уведомлений (видимых на экране). */
        post("/read") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val request = call.receive<MarkNotificationsReadRequest>()
            userNotificationRepository.markRead(sessionDbo.userId, request.ids.take(200))
            call.respondSuccess()
        }

        /** Всё прочитано (вход на экран уведомлений/кнопка). */
        post("/read-all") {
            val sessionDbo = sessionRepository.verifyToken(call)
            userNotificationRepository.markAllRead(sessionDbo.userId)
            call.respondSuccess()
        }
    }
}
