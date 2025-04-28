package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureUpdateChatRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
) {
    /**
     * Update chat request DTO
     */
    @Serializable
    data class UpdateChatRequest(
        val isChatMuted: Boolean? = null
    )

    /**
     * Endpoint to update a chat
     */
    patch("/chats/{chatId}") {
        try {
            // Authenticate user
            val sessionDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@patch call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            // Get chat ID from path parameter
            val chatId = call.parameters["chatId"] ?: return@patch call.respond(
                HttpStatusCode.BadRequest, "Missing chatId parameter"
            )

            // Get chat and verify ownership
            val chat = chatRepository.getChatById(chatId) ?: return@patch call.respond(
                HttpStatusCode.NotFound, "Chat not found"
            )

            if (chat.userId != sessionDbo.userId) {
                return@patch call.respond(
                    HttpStatusCode.Forbidden, "You don't have access to this chat"
                )
            }

            val request = call.receive<UpdateChatRequest>()

            chatRepository.updateChat(
                chatId = chatId,
                isChatMuted = request.isChatMuted
            )

            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(
                HttpStatusCode.InternalServerError,
                "An error occurred processing your request"
            )
        }
    }
}