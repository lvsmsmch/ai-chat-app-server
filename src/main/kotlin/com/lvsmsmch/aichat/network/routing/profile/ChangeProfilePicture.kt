package com.lvsmsmch.aichat.network.routing.profile

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import com.lvsmsmch.aichat.utils.UnauthorizedException
import com.lvsmsmch.aichat.utils.uploadImageOnServer
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import java.io.File

fun Routing.configureChangeProfilePictureRouting(
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
) {
    // Use PATCH for partial update of a resource
    patch("/profile/picture") {
        try {
            val tokenDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@patch call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            var pictureFile: File? = null

            // Process the multipart data
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (part.name == "picture") {
                            val file = File.createTempFile("profile_", ".tmp")
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
                part.dispose() // Don't forget to dispose the part after handling
            }

            // Validate required fields
            if (pictureFile == null) {
                return@patch call.respond(HttpStatusCode.BadRequest, "Profile picture is required")
            }

            // Upload image and update user profile
            val newUrl = uploadImageOnServer(pictureFile!!)
            userRepository.updateUserProfilePicture(
                userId = tokenDbo.userId,
                profilePictureUrl = newUrl
            )

            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}