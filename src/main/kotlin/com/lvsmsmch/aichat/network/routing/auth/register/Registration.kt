package com.lvsmsmch.aichat.network.routing.auth.register

import com.lvsmsmch.aichat.db.repositories.auth.attempts.RegistrationAttemptsTracker
import com.lvsmsmch.aichat.db.repositories.content.UsersRepository
import com.lvsmsmch.aichat.db.repositories.auth.verification_codes.RegistrationCodesRepository
import com.lvsmsmch.aichat.utils.EmailSender
import com.lvsmsmch.aichat.utils.getUserIp
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureRegistrationRouting(
    usersRepository: UsersRepository,
    registrationAttemptsTracker: RegistrationAttemptsTracker,
    registrationCodesRepository: RegistrationCodesRepository,
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

    post("/auth/register") {
        try {
            val request = call.receive<Request>()

            val recentAttempts = registrationAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
                ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 1
            )
            if (recentAttempts > 10) return@post call.respond(HttpStatusCode.TooManyRequests)

            val user = usersRepository.findUserByEmail(request.email)
            if (user != null) {
                return@post call.respond(HttpStatusCode.Forbidden, "This email already registered")
            }

            val codeToBeVerified = if (registrationCodesRepository.canGenerateNewCode(request.email)) {
                val newCodeDbo = registrationCodesRepository.createNewVerificationCode(request.email)
                emailSender.sendLoginVerificationCode(request.email, newCodeDbo.code)
                registrationCodesRepository.save(newCodeDbo)
                newCodeDbo
            } else {
                registrationCodesRepository.getAliveCodeOrNull(request.email)
            }

            if (codeToBeVerified != null) {
                return@post call.respond(
                    HttpStatusCode.OK, CodeSentResponse(
                        sentAt = codeToBeVerified.createdAt.toString(),
                        expiresAt = codeToBeVerified.expiresAt.toString(),
                        nextAvailableAt = codeToBeVerified.lockoutUntil.toString()
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