package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.toChatDto
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*

fun Routing.configureChatRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
    mapper: Mapper
) {

    get("/chats/{chatId}") {
        try {
            val sessionDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@get call.respond(HttpStatusCode.Unauthorized, e.message)
            }
            val chatId = call.parameters["chatId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, "Missing chatId parameter"
            )
            val chat = chatRepository.getChatById(chatId) ?: return@get call.respond(
                HttpStatusCode.NotFound, "Chat not found"
            )
            if (chat.userId != sessionDbo.userId) {
                return@get call.respond(
                    HttpStatusCode.Forbidden, "You don't have access to this chat"
                )
            }
            val chatDto = chat.toChatDto(mapper = mapper)
            call.respond(chatDto)
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(
                HttpStatusCode.InternalServerError,
                "An error occurred processing your request"
            )
        }
    }
}