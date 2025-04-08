package com.lvsmsmch.aichat.network.routing.notifications

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val userNotificationSessions = ConcurrentHashMap<String, MutableList<WebSocketSession>>()

@Serializable
sealed class NotificationMessage {
    abstract val type: String
}

@Serializable
data class NewMessageNotification(
    override val type: String = "new_message",
    val chatId: String,
    val characterName: String,
    val messagePreview: String,
    val timestamp: UtcTimestamp
) : NotificationMessage()

@Serializable
data class UnreadCountNotification(
    override val type: String = "unread_count",
    val totalUnread: Int,
    val chatUnreadCounts: Map<String, Int>
) : NotificationMessage()

@Serializable
data class ErrorNotification(
    override val type: String = "error",
    val code: String,
    val message: String
) : NotificationMessage()

@Serializable
data class PingMessage(
    override val type: String = "ping"
) : NotificationMessage()

@Serializable
data class PongMessage(
    override val type: String = "pong"
) : NotificationMessage()

fun Routing.configureNotificationsWebSocketRouting(
    sessionRepository: SessionRepository,
    json: Json
) {
    webSocket("/notifications/ws") {
        var userId: String? = null

        try {
            // Authenticate the user
            val sessionDbo = sessionRepository.verifyToken(call)
            userId = sessionDbo.userId

            // Add this session to the user's active sessions
            userNotificationSessions.computeIfAbsent(userId) { mutableListOf() }.add(this)

            // Process incoming messages (mostly ping/pong for keeping connection alive)
            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()

                    // Simple ping/pong mechanism to keep the connection alive
                    if (text.contains("\"type\":\"ping\"")) {
                        sendMessage(PongMessage(), json)
                    }
                }
            }

        } catch (e: UnauthorizedException) {
            sendMessage(ErrorNotification(code = "auth_error", message = e.message), json)
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
        } catch (e: ClosedReceiveChannelException) {
            // Normal close, no need to do anything
        } catch (e: Exception) {
            sendMessage(ErrorNotification(code = "error", message = "An unexpected error occurred"), json)
        } finally {
            // Remove this session when it's closed
            if (userId != null) {
                userNotificationSessions[userId]?.remove(this)
                // Clean up empty lists
                if (userNotificationSessions[userId]?.isEmpty() == true) {
                    userNotificationSessions.remove(userId)
                }
            }
        }
    }
}

private suspend fun WebSocketSession.sendMessage(message: NotificationMessage, json: Json) {
    send(Frame.Text(json.encodeToString(message)))
}

/**
 * Sends a new message notification to all connected sessions for a user
 */
suspend fun sendNewMessageNotification(
    userId: String,
    chatId: String,
    characterName: String,
    messagePreview: String,
    timestamp: UtcTimestamp,
    json: Json
) {
    val notification = NewMessageNotification(
        chatId = chatId,
        characterName = characterName,
        messagePreview = messagePreview,
        timestamp = timestamp
    )

    userNotificationSessions[userId]?.forEach { session ->
        try {
            session.sendMessage(notification, json)
        } catch (e: Exception) {
            // Log error but continue with other sessions
            println("Error sending notification to session: ${e.message}")
        }
    }
}

/**
 * Sends unread message counts to all connected sessions for a user
 */
suspend fun sendUnreadCountNotification(
    userId: String,
    totalUnread: Int,
    chatUnreadCounts: Map<String, Int>,
    json: Json
) {
    val notification = UnreadCountNotification(
        totalUnread = totalUnread,
        chatUnreadCounts = chatUnreadCounts
    )

    userNotificationSessions[userId]?.forEach { session ->
        try {
            session.sendMessage(notification, json)
        } catch (e: Exception) {
            // Log error but continue with other sessions
            println("Error sending notification to session: ${e.message}")
        }
    }
}