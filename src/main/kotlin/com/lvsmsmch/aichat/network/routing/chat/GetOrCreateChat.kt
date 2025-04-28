package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.toChatDto
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.CharacterRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*

fun Routing.configureGetOrCreateChatRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
    characterRepository: CharacterRepository,
    mapper: Mapper
) {
    /**
     * Endpoint to get a chat by character ID, creating one if it doesn't exist
     */
    get("/characters/{characterId}/chat") {
        try {
            val sessionDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@get call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            val characterId = call.parameters["characterId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, "Missing characterId parameter"
            )

            // Check if character exists
            val characterDbo = characterRepository.getCharacter(characterId) ?: return@get call.respond(
                HttpStatusCode.NotFound, "Character not found"
            )

            if (!characterDbo.isPublic && characterDbo.authorId != sessionDbo.userId) {
                return@get call.respond(HttpStatusCode.Forbidden, "This character is private.")
            }

            // Try to find an existing chat with this character for this user
            val existingChat = chatRepository.findChatByUserAndCharacter(
                userId = sessionDbo.userId,
                characterId = characterId
            )

            val chat = existingChat ?: chatRepository.createChat(
                userId = sessionDbo.userId,
                characterId = characterId,
                characterName = characterDbo.name,
                characterDescription = characterDbo.description,
                characterPrompt = characterDbo.prompt,
                characterPicUrl = characterDbo.picUrl,
                characterIsPublic = characterDbo.isPublic
            )
            val chatDto = chat.toChatDto(mapper)
            val statusCode = if (existingChat == null) HttpStatusCode.Created else HttpStatusCode.OK
            call.respond(statusCode, chatDto)

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(
                HttpStatusCode.InternalServerError,
                "An error occurred processing your request"
            )
        }
    }
}