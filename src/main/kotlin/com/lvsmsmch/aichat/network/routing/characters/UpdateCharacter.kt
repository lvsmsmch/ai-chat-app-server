package com.lvsmsmch.aichat.network.routing.characters

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.CharacterRepository
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import com.lvsmsmch.aichat.utils.ImageServer
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import java.io.File

fun Routing.configureUpdateCharacterRouting(
    sessionRepository: SessionRepository,
    characterRepository: CharacterRepository,
) {
    patch("/characters/{id}") {
        try {
            val sessionDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@patch call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            val characterId = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing character id")

            val character = characterRepository.getCharacter(characterId)
                ?: return@patch call.respond(HttpStatusCode.NotFound, "Character not found")

            if (character.authorId != sessionDbo.userId) {
                return@patch call.respond(HttpStatusCode.Forbidden, "You can't update this character")
            }

            // Check content type to ensure it's multipart/form-data
            val contentType = call.request.contentType()
            if (!contentType.match(ContentType.MultiPart.FormData)) {
                return@patch call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    "Only multipart/form-data is supported for this endpoint"
                )
            }

            // Process multipart form data
            var name: String? = null
            var description: String? = null
            var prompt: String? = null
            var pictureFile: File? = null

            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        when (part.name) {
                            "name" -> name = part.value.takeIf { it.isNotBlank() }
                            "description" -> description = part.value.takeIf { it.isNotBlank() }
                            "prompt" -> prompt = part.value.takeIf { it.isNotBlank() }
                        }
                    }

                    is PartData.FileItem -> {
                        if (part.name == "picture") {
                            val file = File.createTempFile("upload_", ".tmp")
                            part.streamProvider().use { input ->
                                file.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            pictureFile = file
                        }
                    }

                    else -> {} // Ignore other part types
                }
                part.dispose()
            }

            // Check if there are any fields to update
            if (name == null && description == null && prompt == null && pictureFile == null) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    "No fields provided for update"
                )
            }

            // Validate fields if provided
            name?.let {
                if (it.length > 100) {
                    return@patch call.respond(HttpStatusCode.BadRequest, "Name exceeded 100 characters")
                }
            }

            description?.let {
                if (it.length > 1000) {
                    return@patch call.respond(HttpStatusCode.BadRequest, "Description exceeded 1000 characters")
                }
            }

            prompt?.let {
                if (it.length > 1000) {
                    return@patch call.respond(HttpStatusCode.BadRequest, "Prompt exceeded 1000 characters")
                }
            }

            // Upload new picture if provided
            val pictureUrl = pictureFile?.let { ImageServer.uploadImageOnServer(it) } ?: ""

            characterRepository.updateCharacter(
                characterId = characterId,
                name = name,
                description = description,
                prompt = prompt,
                pictureUrl = pictureUrl
            )

            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}