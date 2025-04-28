package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.db.repositories.content.MessageRepository
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*

fun Routing.configureDeleteChatRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository
) {


    delete("/chats/{chatId}") {
        try {
            // Authenticate user
            val sessionDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@delete call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            // Get chat ID from path parameter
            val chatId = call.parameters["chatId"] ?: return@delete call.respond(
                HttpStatusCode.BadRequest, "Missing chatId parameter"
            )

            // Get chat and verify ownership
            val chat = chatRepository.getChatById(chatId) ?: return@delete call.respond(
                HttpStatusCode.NotFound, "Chat not found"
            )

            if (chat.userId != sessionDbo.userId) {
                return@delete call.respond(
                    HttpStatusCode.Forbidden, "You don't have access to this chat"
                )
            }

            messageRepository.deleteAllMessagesInChat(chatId)
            chatRepository.deleteChat(chatId)

            call.respond(HttpStatusCode.NoContent)
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}