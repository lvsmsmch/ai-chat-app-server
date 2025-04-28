package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.toMessageDto
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.db.repositories.content.MessageRepository
import com.lvsmsmch.aichat.network.dto_objects.MessageDto
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureMessageHistoryRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    mapper: Mapper
) {

    @Serializable
    data class MessageHistoryResponse(
        val messages: List<MessageDto>,
        val totalCount: Int
    )

    get("/chats/{chatId}/message_history") {
        try {
            // Authenticate user
            val sessionDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@get call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            // Get chat ID from path parameter
            val chatId = call.parameters["chatId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, "Missing chatId parameter"
            )

            // Get chat and verify ownership
            val chat = chatRepository.getChatById(chatId) ?: return@get call.respond(
                HttpStatusCode.NotFound, "Chat not found"
            )

            if (chat.userId != sessionDbo.userId) {
                return@get call.respond(
                    HttpStatusCode.Forbidden, "You don't have access to this chat"
                )
            }

            // Get pagination parameters
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0
            val descending = call.request.queryParameters["descending"]?.toBoolean() ?: false

            // Get messages
            val messages = messageRepository.getMessagesByChatId(
                chatId = chatId,
                limit = limit,
                skip = skip,
                descending = descending
            )

            // Get total count for pagination
            val totalCount = messageRepository.countMessagesInChat(chatId)

            // Convert to DTOs
            val messageDtos = messages.map { it.toMessageDto(mapper) }

            // Mark character messages as read
            messageRepository.markAllCharacterMessagesAsRead(chatId)

            // Respond with messages
            call.respond(
                MessageHistoryResponse(
                    messages = messageDtos,
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