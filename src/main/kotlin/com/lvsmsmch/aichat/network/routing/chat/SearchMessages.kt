package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.utils.MatchPositions
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

fun Routing.configureMessageHistorySearchRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    mapper: Mapper
) {

    @Serializable
    data class MessageMatch(
        val message: MessageDto,
        val matchPositions: MatchPositions
    )

    @Serializable
    data class MessageSearchResponse(
        val matches: List<MessageMatch>
    )


    get("/chats/{chatId}/messages/search") {
        try {
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


            // Get search query
            val searchQuery = call.request.queryParameters["searchQuery"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, "Missing searchQuery parameter"
            )


            val searchResults = messageRepository.searchMessagesInChat(searchQuery, chatId)

            val matches = searchResults.map { (message, matchPositions) ->
                MessageMatch(
                    message = message.toMessageDto(mapper),
                    matchPositions = matchPositions
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