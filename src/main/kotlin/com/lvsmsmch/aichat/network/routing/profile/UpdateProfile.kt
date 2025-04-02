package com.lvsmsmch.aichat.network.routing.profile

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.UsersRepository
import com.lvsmsmch.aichat.utils.UnauthorizedException
import com.lvsmsmch.aichat.utils.hashPassword
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureProfileUpdatesRouting(
    usersRepository: UsersRepository,
    sessionRepository: SessionRepository,
) {

    @Serializable
    data class Updates(
        val password: String? = null,
        val username: String? = null,
        val name: String? = null,
    )

    patch("/profile") {
        try {
            val tokenDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@patch call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            val updates = call.receive<Updates>()

            if (updates.password == null && updates.username == null && updates.name == null) {
                return@patch call.respond(HttpStatusCode.BadRequest, "No update fields provided")
            }

            updates.password?.let { usersRepository.updatePassword(tokenDbo.userId, hashPassword(it)) }
            updates.username?.let { usersRepository.updateUsername(tokenDbo.userId, it) }
            updates.name?.let { usersRepository.updateName(tokenDbo.userId, it) }

            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}