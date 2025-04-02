package com.lvsmsmch.aichat.network.routing.characters

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.CharactersRepository
import com.lvsmsmch.aichat.utils.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*

fun Routing.configureDeleteCharacterRouting(
    charactersRepository: CharactersRepository,
    sessionRepository: SessionRepository,
) {

    delete("/characters/{id}") {
        try {
            val tokenDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@delete call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            val characterId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")

            val character = charactersRepository.getCharacter(characterId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, "Character not found")

            if (character.publisherId != tokenDbo.userId) {
                return@delete call.respond(HttpStatusCode.Forbidden, "You can't delete this character")
            }

            try {
                charactersRepository.deleteCharacter(characterId = characterId)
            } catch (e: Exception) {
                return@delete call.respond(HttpStatusCode.InternalServerError, e.message.toString())
            }

            call.respond(HttpStatusCode.NoContent)

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}