package com.lvsmsmch.aichat.network.routing.auth.password_reset

import com.lvsmsmch.aichat.db.repositories.auth.tokens.single_use_tokens.SetNewPasswordTokenRepository
import com.lvsmsmch.aichat.db.repositories.auth.tokens.verification_tokens.PasswordResetTokenRepository
import com.lvsmsmch.aichat.utils.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureVerifyPasswordResetRouting(
    passwordResetTokenRepository: PasswordResetTokenRepository,
    setNewPasswordTokenRepository: SetNewPasswordTokenRepository,
) {

    @Serializable
    data class Response(
        val email: String,
        val setNewPasswordToken: String
    )

    post("/auth/verify-password-reset") {
        try {
            val tokenDbo = try {
                passwordResetTokenRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@post call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            val email = tokenDbo.email

            val setNewPasswordToken = setNewPasswordTokenRepository.createAndSaveNewToken(email)

            return@post call.respond(
                HttpStatusCode.OK, Response(
                    email = email,
                    setNewPasswordToken = setNewPasswordToken.token
                )
            )
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}