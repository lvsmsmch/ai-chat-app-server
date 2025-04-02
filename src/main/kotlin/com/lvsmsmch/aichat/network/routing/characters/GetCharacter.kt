package com.lvsmsmch.aichat.network.routing.characters

import com.lvsmsmch.aichat.db.repositories.content.CharactersRepository
import com.lvsmsmch.aichat.network.dto_objects.CharacterDto
import com.lvsmsmch.aichat.utils.toCharacterDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureGetCharacterRouting(
    charactersRepository: CharactersRepository
) {

    @Serializable
    data class Response(
        val character: CharacterDto
    )

    get("/characters/{id}") {
        try {
            val characterId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing character id")

            val characterDbo = charactersRepository.getCharacter(characterId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Character not found")

            val characterDto = characterDbo.toCharacterDto()

            call.respond(HttpStatusCode.OK, Response(characterDto))

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}