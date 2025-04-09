package com.lvsmsmch.aichat.network.routing.notifications

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.db.repositories.content.MessageDbo
import com.lvsmsmch.aichat.db.repositories.content.MessageRepository
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.defaultJson
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

@Serializable
sealed class NotificationWsRequest {

    @Serializable
    @SerialName("ping")
    object Ping : NotificationWsRequest()

}


@Serializable
sealed class NotificationWsEvent {

    @Serializable
    @SerialName("pong")
    object Pong : NotificationWsEvent()

    @Serializable
    @SerialName("chat_state_changed")
    data class ChatStateChanged(
        val lastMessageDbo: MessageDbo,
        val unreadMessages: Int,
        val unreadChats: Int,
    ) : NotificationWsEvent()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String
    ) : NotificationWsEvent()

}


fun Routing.configureNotificationsWebSocketRouting(
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
    chatRepository: ChatRepository
) {
    val notificationSessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    webSocket("/notifications/ws") {
        var userId: String? = null

        try {
            // Authenticate the user
            val sessionDbo = sessionRepository.verifyToken(call)
            userId = sessionDbo.userId

            // Add this session to the user's active sessions
            notificationSessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(this)


            // Process incoming messages (mostly ping/pong for keeping connection alive)
            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()

                    if (text.contains("\"type\":\"ping\"")) {
                        sendEvent(NotificationWsEvent.Pong)
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // Normal close, no need to do anything
        } catch (e: Exception) {
            sendEvent(NotificationWsEvent.Error(message = "Internal server error"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message ?: "Unknown error"))
        } finally {
            if (userId != null) {
                notificationSessions[userId]?.remove(this)
                if (notificationSessions[userId]?.isEmpty() == true) {
                    notificationSessions.remove(userId)
                }
            }
        }
    }
}

private suspend fun WebSocketSession.sendEvent(message: NotificationWsEvent) {
    send(Frame.Text(defaultJson.encodeToString(message)))
}