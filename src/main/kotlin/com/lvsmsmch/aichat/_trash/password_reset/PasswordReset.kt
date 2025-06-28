package com.lvsmsmch.aichat._trash.password_reset

import com.lvsmsmch.aichat._trash.PasswordResetTokenRepository
import com.lvsmsmch.aichat._trash.VerificationTokenDbo
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.CantSendTokenTooManyAttemptsException
import com.lvsmsmch.aichat.utils.UserWithThisEmailNotFoundException
import com.lvsmsmch.aichat.utils.EmailSender
import com.lvsmsmch.aichat.utils.respondSuccess
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
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
        val request = call.receive<Request>()

        userRepository.findUserByEmail(request.email) ?: throw UserWithThisEmailNotFoundException()

        var token: VerificationTokenDbo?

        token = passwordResetTokenRepository.getTokenByEmail(request.email)
            ?.takeIf { !it.isUsed && !it.isExpired }

        if (passwordResetTokenRepository.canGenerateNewToken(request.email)) {
            token = passwordResetTokenRepository.createAndSaveNewToken(request.email)
        }

        if (token == null) {
            throw CantSendTokenTooManyAttemptsException()
        }

        emailSender.sendPasswordResetVerificationToken(request.email, token.token)

        call.respondSuccess(
            data = TokenSentResponse(
                sentAt = token.createdAt.toString(),
                expiresAt = token.expiresAt.toString(),
                nextAvailableAt = token.lockoutUntil.toString()
            )
        )
    }
}