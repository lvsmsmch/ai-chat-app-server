package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.toChatDto
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.db.repositories.content.MessageRepository
import com.lvsmsmch.aichat.network.dto_objects.ChatDto
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureGetChatsRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    mapper: Mapper
) {
    @Serializable
    data class ChatWithLastMessage(
        val chat: ChatDto,
        val lastMessage: String?,
        val lastMessageTimestamp: String?,
        val unreadCount: Int
    )

    @Serializable
    data class ChatsResponse(
        val chats: List<ChatWithLastMessage>,
        val totalCount: Long
    )

    get("/chats") {
        try {
            // Authenticate user
            val sessionDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@get call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            // Get pagination parameters
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0

            // Get total count first for pagination information
            val totalCount = chatRepository.countChatsByUserId(sessionDbo.userId)

            // Get chats with pagination
            val chatsList = chatRepository.getChatsByUserId(sessionDbo.userId)
                .sortedByDescending { it.createdAt.instant } // Sort by most recent
                .drop(skip)
                .take(limit)

            // Enhance chats with last message and unread count information
            val enhancedChats = chatsList.map { chat ->
                // Get the last message
                val lastMessage = messageRepository.getLastMessageInChat(chat.id)

                // Count unread messages
                val unreadCount = messageRepository.countUnreadMessagesInChat(chat.id).toInt()

                ChatWithLastMessage(
                    chat = chat.toChatDto(mapper),
                    lastMessage = lastMessage?.text,
                    lastMessageTimestamp = lastMessage?.createdAt?.toString(),
                    unreadCount = unreadCount
                )
            }

            call.respond(
                ChatsResponse(
                    chats = enhancedChats,
                    totalCount = totalCount
                )
            )

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(
                HttpStatusCode.InternalServerError,
                "An error occurred processing your request"
            )
        }
    }
}