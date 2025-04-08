package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.MatchPositions
import com.lvsmsmch.aichat.db.repositories._utils.toChatDto
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.network.dto_objects.ChatDto
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureChatsSearchRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
    mapper: Mapper
) {

    @Serializable
    data class ChatMatch(
        val chat: ChatDto,
        val matchPositions: MatchPositions,
    )

    @Serializable
    data class ChatSearchResponse(
        val matches: List<ChatMatch>
    )

    // Search for chats by character name
    get("/chats/search") {
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

            // Search chats
            val searchResults = chatRepository.searchChatsByCharacterName(
                searchText = searchQuery,
                userId = sessionDbo.userId
            )

            // Map to response format
            val matches = searchResults.map { (chat, matches) ->
                ChatMatch(chat = chat.toChatDto(mapper), matchPositions = matches)
            }

            call.respond(ChatSearchResponse(matches = matches))

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(
                HttpStatusCode.InternalServerError,
                "An error occurred processing your request"
            )
        }
    }
}