package com.lvsmsmch.aichat._trash.login

import com.lvsmsmch.aichat._trash.attempts.LoginAttemptsTracker
import com.lvsmsmch.aichat._trash.verification_codes.LoginCodesRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Routing.configureLoginRouting(
    userRepository: UserRepository,
    loginAttemptsTracker: LoginAttemptsTracker,
    loginCodesRepository: LoginCodesRepository,
    emailSender: EmailSender,
) {
    @Serializable
    data class Request(
        val email: String,
        val password: String,
    )

    @Serializable
    data class CodeSentResponse(
        val sentAt: String,
        val expiresAt: String,
        val nextAvailableAt: String,
    )

    post("/auth/login") {
        val request = call.receive<Request>()

        validateUserEmail(request.email)
        validateUserPassword(request.password)

        val recentAttempts = loginAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
            ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 1
        )

        if (recentAttempts > 10) {
            throw TooManyAttemptsException()
        }

        val user = userRepository.findUserByEmail(request.email)
        if (user == null) {
            loginAttemptsTracker.recordAttempt(call.getUserIp(), request.email, false)
            throw UserWithThisEmailNotFoundException()
        }

        if (user.hashedPassword != null && !checkPassword(request.password, user.hashedPassword)) {
            loginAttemptsTracker.recordAttempt(call.getUserIp(), request.email, false)
            throw WrongPasswordException()
        }

        loginAttemptsTracker.recordAttempt(call.getUserIp(), request.email, true)

        val codeToBeVerified = if (loginCodesRepository.canGenerateNewCode(request.email)) {
            val newCodeDbo = loginCodesRepository.createNewVerificationCode(request.email)
            emailSender.sendLoginVerificationCode(request.email, newCodeDbo.code)
            loginCodesRepository.save(newCodeDbo)
            newCodeDbo
        } else {
            loginCodesRepository.getAliveCodeOrNull(request.email)
        }

        if (codeToBeVerified == null) {
            throw CantSendTokenTooManyAttemptsException()
        }

        return@post call.respondSuccess(
            data = CodeSentResponse(
                sentAt = codeToBeVerified.createdAt.toString(),
                expiresAt = codeToBeVerified.expiresAt.toString(),
                nextAvailableAt = codeToBeVerified.lockoutUntil.toString()
            )
        )
    }
}