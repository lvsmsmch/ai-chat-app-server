package com.lvsmsmch.aichat.network.routing.profile

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*

fun Routing.configureDeleteProfileRouting(
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
) {

    delete("/profile") {
        try {
            val tokenDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@delete call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            userRepository.deleteUser(userId = tokenDbo.userId)
            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}