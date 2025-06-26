package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.concurrent.ConcurrentHashMap

val webSocketServices: ConcurrentHashMap<String, ChatWebSocketService> = ConcurrentHashMap()

fun Routing.configureChatsWebSocketRouting(
    sessionRepository: SessionRepository,
    characterRepository: CharacterRepository,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    characterActivityLogRepository: CharacterActivityLogRepository,
    idGenerator: IdGenerator,
    mapper: Mapper
) {
    webSocket("/chats/ws") {
        val userId = try {
            sessionRepository.verifyToken(call).userId
        } catch (e: Exception) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message ?: "Unauthorized"))
            return@webSocket
        }

        var service: ChatWebSocketService? = null

        try {
            service = webSocketServices.computeIfAbsent(userId) {
                ChatWebSocketService(
                    application = call.application,
                    userId = userId,
                    chatRepository = chatRepository,
                    messageRepository = messageRepository,
                    characterRepository = characterRepository,
                    characterActivityLogRepository = characterActivityLogRepository,
                    idGenerator = idGenerator,
                    mapper = mapper,
                    onShutdown = { webSocketServices.remove(userId) }
                )
            }

            // Add connection - this can throw exceptions
            service.addConnection(this)

            // Keep the WebSocket alive and handle incoming frames
            // The actual frame processing is handled in the service
            try {
                for (frame in incoming) {
                    // Frames are processed in the service's consuming job
                    // This loop just keeps the WebSocket connection alive
                }
            } catch (e: Exception) {
                application.log.debug("WebSocket connection closed: ${e.message}")
            }

        } catch (e: Exception) {
            application.log.error("WebSocket setup error for user $userId", e)
            try {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message ?: "Unknown error"))
            } catch (closeException: Exception) {
                application.log.debug("Error closing WebSocket: ${closeException.message}")
            }
        } finally {
            try {
                service?.removeConnection(this)
            } catch (e: Exception) {
                application.log.error("Error during WebSocket cleanup for user $userId", e)
            }
        }
    }
}