package com.lvsmsmch.aichat.network.routing.characters

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.toCharacterDetailsDto
import com.lvsmsmch.aichat.db.repositories.content.CharacterRepository
import com.lvsmsmch.aichat.network.dto_objects.CharacterDetailsDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureGetCharacterDetailsRouting(
    characterRepository: CharacterRepository,
    mapper: Mapper,
) {

    @Serializable
    data class Response(
        val character: CharacterDetailsDto
    )

    get("/characters/{id}/details") {
        try {
            val characterId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing character id")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Character not found")

            val characterDetailsDto = characterDbo.toCharacterDetailsDto(mapper)

            call.respond(HttpStatusCode.OK, Response(characterDetailsDto))

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}