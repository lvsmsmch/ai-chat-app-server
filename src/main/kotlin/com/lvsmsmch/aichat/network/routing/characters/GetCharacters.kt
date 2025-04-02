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

fun Routing.configureGetCharactersRouting(
    charactersRepository: CharactersRepository,
) {

    @Serializable
    data class Response(
        val characters: List<CharacterDto>
    )

    get("/characters") {
        try {
            val searchQuery = call.request.queryParameters["searchQuery"] ?: ""
            val filter = call.request.queryParameters["filter"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
            val skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0

            val charactersDbo = charactersRepository.getCharacters(searchQuery, filter, limit, skip)

            val charactersDto = charactersDbo.map { it.toCharacterDto() }

            call.respond(HttpStatusCode.OK, Response(charactersDto))

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}