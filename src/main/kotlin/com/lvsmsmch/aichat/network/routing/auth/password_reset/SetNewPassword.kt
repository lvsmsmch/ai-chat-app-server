package com.lvsmsmch.aichat.network.routing.auth.password_reset

import com.lvsmsmch.aichat.db.repositories.auth.tokens.single_use_tokens.SetNewPasswordTokenRepository
import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import com.lvsmsmch.aichat.utils.UnauthorizedException
import com.lvsmsmch.aichat.utils.hashPassword
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureSetNewPasswordRouting(
    userRepository: UserRepository,
    setNewPasswordTokenRepository: SetNewPasswordTokenRepository,
) {

    @Serializable
    data class Request(
        val password: String
    )

    post("/auth/reset-password") {
        try {
            val request = call.receive<Request>()

            val tokenDbo = try {
                setNewPasswordTokenRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@post call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            val userDbo = userRepository.getUserById(tokenDbo.userId)
                ?: return@post call.respond(HttpStatusCode.NotFound, "User with this email not found")

            userRepository.updatePassword(userId = userDbo.id, hashedPassword = hashPassword(request.password))
            setNewPasswordTokenRepository.markAsUsedByToken(tokenDbo.token)
            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}