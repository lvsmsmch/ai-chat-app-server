package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.MatchPositions
import com.lvsmsmch.aichat.db.repositories._utils.toChatDto
import com.lvsmsmch.aichat.db.repositories._utils.toMessageDto
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable
import com.lvsmsmch.aichat.db.repositories.content.MessageRepository
import com.lvsmsmch.aichat.network.dto_objects.ChatDto
import com.lvsmsmch.aichat.network.dto_objects.MessageDto

fun Routing.configureSearchRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    mapper: Mapper
) {

    // Message search response classes
    @Serializable
    data class MessageMatch(
        val message: MessageDto,
        val chat: ChatDto,
        val matchPositions: MatchPositions
    )

    @Serializable
    data class MessageSearchResponse(
        val matches: List<MessageMatch>
    )

    // Search for messages across all chats
    get("/messages/search") {
        try {
            // Authenticate user
            val sessionDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@get call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            // Get search query
            val searchQuery = call.request.queryParameters["searchQuery"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, "Missing searchQuery parameter"
            )

            // Search messages across all user chats
            val searchResults = messageRepository.searchMessagesByContentInAllChats(
                searchText = searchQuery,
                userId = sessionDbo.userId
            )

            // Get chats
            val chatMap = searchResults.keys
                .map { it.chatId }
                .distinct()
                .let { chatIds -> chatRepository.getChatsByIds(chatIds) }
                .associateBy { it.id }

            // Map to response format
            val matches = searchResults.map { (message, matches) ->
                val chat = chatMap[message.chatId] ?: throw Exception(
                    "No chat found with ID ${message.chatId} (message ID: ${message.id})"
                )
                MessageMatch(
                    message = message.toMessageDto(mapper),
                    chat = chat.toChatDto(mapper),
                    matchPositions = matches
                )
            }

            call.respond(MessageSearchResponse(matches = matches))

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(
                HttpStatusCode.InternalServerError,
                "An error occurred processing your request"
            )
        }
    }
}