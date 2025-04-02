package com.lvsmsmch.aichat.network.routing.auth.register

import com.lvsmsmch.aichat.db.repositories.auth.tokens.single_use_tokens.RegistrationCompletionTokenRepository
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.UserDbo
import com.lvsmsmch.aichat.db.repositories.content.UsersRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureCompleteRegistrationRouting(
    usersRepository: UsersRepository,
    registrationCompletionTokensRepository: RegistrationCompletionTokenRepository,
    sessionRepository: SessionRepository,
) {

    @Serializable
    data class Request(
        val token: String,
        val password: String,
        val username: String,
        val name: String,
        val isAgreedToNews: String,
    )

    @Serializable
    data class Response(
        val sessionToken: String
    )

    post("/auth/complete-registration") {
        try {
            val request = call.receive<Request>()

            val tokenDbo = try {
                registrationCompletionTokensRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@post call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            val user = usersRepository.findUserByEmail(tokenDbo.email)
            if (user != null) {
                return@post call.respond(HttpStatusCode.Forbidden, "User already exists")
            }

            val newUserDbo = UserDbo(
                email = tokenDbo.email,
                hashedPassword = hashPassword(request.password),
                username = request.username,
                name = request.name,
            )
            usersRepository.addUser(newUserDbo)
            val sessionDbo = sessionRepository.createSession(newUserDbo.id, call.getUserIp())

            registrationCompletionTokensRepository.markAsUsedByToken(tokenDbo.token)

            call.respond(HttpStatusCode.OK, Response(sessionToken = sessionDbo.token))
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}