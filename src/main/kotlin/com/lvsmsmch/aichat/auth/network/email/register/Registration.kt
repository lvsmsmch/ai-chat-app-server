package com.lvsmsmch.aichat.auth.network.email.register

import com.lvsmsmch.aichat.auth.database.attempts.RegistrationAttemptsTracker
import com.lvsmsmch.aichat.auth.database.verification_codes.RegistrationCodesRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Routing.configureRegistrationRouting(
    userRepository: UserRepository,
    registrationAttemptsTracker: RegistrationAttemptsTracker,
    registrationCodesRepository: RegistrationCodesRepository,
    emailSender: EmailSender,
) {

    @Serializable
    data class Request(
        val email: String
    )

    @Serializable
    data class CodeSentResponse(
        val sentAt: String,
        val expiresAt: String,
        val nextAvailableAt: String,
    )

    post("/auth/register") {
        val request = call.receive<Request>()

        validateUserEmail(request.email)

        val recentAttempts = registrationAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
            ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 1
        )
        if (recentAttempts > 10) {
            throw TooManyAttemptsException()
        }

        val user = userRepository.findUserByEmail(request.email)
        if (user != null) {
            throw EmailAlreadyInUseException()
        }

        val codeToBeVerified = if (registrationCodesRepository.canGenerateNewCode(request.email)) {
            val newCodeDbo = registrationCodesRepository.createNewVerificationCode(request.email)
            emailSender.sendRegistrationVerificationCode(request.email, newCodeDbo.code)
            registrationCodesRepository.save(newCodeDbo)
            newCodeDbo
        } else {
            registrationCodesRepository.getAliveCodeOrNull(request.email)
        }

        if (codeToBeVerified == null) {
            throw CantSendCodeTooManyAttemptsException()
        }

        call.respondSuccess(
            data = CodeSentResponse(
                sentAt = codeToBeVerified.createdAt.toString(),
                expiresAt = codeToBeVerified.expiresAt.toString(),
                nextAvailableAt = codeToBeVerified.lockoutUntil.toString()
            )
        )
    }
}