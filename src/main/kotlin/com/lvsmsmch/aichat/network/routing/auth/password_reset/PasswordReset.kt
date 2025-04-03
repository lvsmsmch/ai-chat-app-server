package com.lvsmsmch.aichat.network.routing.auth.password_reset

import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import com.lvsmsmch.aichat.db.repositories.auth.tokens.verification_tokens.PasswordResetTokenRepository
import com.lvsmsmch.aichat.utils.EmailSender
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable


fun Routing.configurePasswordResetRouting(
    userRepository: UserRepository,
    passwordResetTokenRepository: PasswordResetTokenRepository,
    emailSender: EmailSender,
) {
    @Serializable
    data class Request(
        val email: String
    )

    @Serializable
    data class TokenSentResponse(
        val sentAt: String,
        val expiresAt: String,
        val nextAvailableAt: String
    )

    post("/auth/request-password-reset") {
        try {
            val request = call.receive<Request>()

            userRepository.findUserByEmail(request.email)
                ?: return@post call.respond(HttpStatusCode.Forbidden, "User with this email not found.")

            val token = if (passwordResetTokenRepository.canGenerateNewToken(request.email)) {
                val newTokenDbo = passwordResetTokenRepository.createNewVerificationToken(request.email)
                emailSender.sendPasswordResetVerificationToken(request.email, newTokenDbo.token)
                passwordResetTokenRepository.save(newTokenDbo)
                newTokenDbo
            } else {
                passwordResetTokenRepository.getAliveTokenOrNull(request.email)
            }

            if (token != null) {
                return@post call.respond(
                    HttpStatusCode.OK, TokenSentResponse(
                        sentAt = token.createdAt.toString(),
                        expiresAt = token.expiresAt.toString(),
                        nextAvailableAt = token.lockoutUntil.toString()
                    )
                )
            }

            return@post call.respond(HttpStatusCode.Forbidden, "Can't generate new verification code yet.")
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}