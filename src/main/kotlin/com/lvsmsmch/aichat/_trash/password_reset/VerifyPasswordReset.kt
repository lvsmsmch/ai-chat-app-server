package com.lvsmsmch.aichat._trash.password_reset

import com.lvsmsmch.aichat._trash.single_use_tokens.SetNewPasswordTokenRepository
import com.lvsmsmch.aichat._trash.PasswordResetTokenRepository
import com.lvsmsmch.aichat.utils.respondSuccess
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Routing.configureVerifyPasswordResetRouting(
    passwordResetTokenRepository: PasswordResetTokenRepository,
    setNewPasswordTokenRepository: SetNewPasswordTokenRepository,
) {

    @Serializable
    data class Response(
        val setNewPasswordToken: String
    )

    post("/auth/verify-password-reset") {
        val tokenDbo = passwordResetTokenRepository.verifyToken(call)
        passwordResetTokenRepository.markAsUsed(tokenDbo.token)
        passwordResetTokenRepository.resetLockout(tokenDbo.token)
        val setNewPasswordToken = setNewPasswordTokenRepository.createAndSaveNewToken(tokenDbo.email)
        call.respondSuccess(data = Response(setNewPasswordToken.token))
    }
}