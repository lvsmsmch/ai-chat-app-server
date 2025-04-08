package com.lvsmsmch.aichat.network.routing.characters

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.CharacterRepository
import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import com.lvsmsmch.aichat.utils.uploadImageOnServer
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import java.io.File

fun Routing.configureAddCharacterRouting(
    sessionRepository: SessionRepository,
    characterRepository: CharacterRepository,
    userRepository: UserRepository,
) {

    post("/characters") {
        try {
            val tokenDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@post call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            var name = ""
            var description = ""
            var prompt = ""
            var pictureFile: File? = null

            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        when (part.name) {
                            "name" -> name = part.value
                            "description" -> description = part.value
                            "prompt" -> prompt = part.value
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

            if (name.isBlank() || description.isBlank() || prompt.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, "Missing required fields")
            }

            if (name.length > 100) {
                return@post call.respond(HttpStatusCode.BadRequest, "Name exceeded 100 characters")
            }

            if (description.length > 1000) {
                return@post call.respond(HttpStatusCode.BadRequest, "Description exceeded 1000 characters")
            }

            if (prompt.length > 1000) {
                return@post call.respond(HttpStatusCode.BadRequest, "Prompt exceeded 1000 characters")
            }

            val pictureUrl = pictureFile?.let { uploadImageOnServer(it) } ?: ""

            characterRepository.addCharacter(
                publisherId = tokenDbo.userId,
                name = name,
                description = description,
                prompt = prompt,
                pictureUrl = pictureUrl,
            )

            call.respond(HttpStatusCode.Created)

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}