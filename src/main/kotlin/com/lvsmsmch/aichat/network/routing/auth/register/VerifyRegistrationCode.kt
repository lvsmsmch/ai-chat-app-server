package com.lvsmsmch.aichat.network.routing.auth.register

import com.lvsmsmch.aichat.db.repositories.auth.attempts.EnterRegistrationCodeAttemptsTracker
import com.lvsmsmch.aichat.db.repositories.auth.verification_codes.RegistrationCodesRepository
import com.lvsmsmch.aichat.db.repositories.auth.tokens.single_use_tokens.RegistrationCompletionTokenRepository
import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import com.lvsmsmch.aichat.utils.getUserIp
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureVerifyRegistrationCodeRouting(
    userRepository: UserRepository,
    enterRegistrationCodeAttemptsTracker: EnterRegistrationCodeAttemptsTracker,
    registrationCodesRepository: RegistrationCodesRepository,
    registrationCompletionTokensRepository: RegistrationCompletionTokenRepository
) {

    @Serializable
    data class Request(
        val email: String,
        val code: Int
    )

    @Serializable
    data class Response(
        val tokenForCompleteRegistration: String
    )

    post("/auth/verify-registration-code") {
        try {
            // Parse request
            val request = call.receive<Request>()

            // Check rate limiting
            val recentAttempts = enterRegistrationCodeAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
                ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 1
            )

            if (recentAttempts > 5) {
                return@post call.respond(HttpStatusCode.TooManyRequests)
            }

            // Validate verification code
            val codeDbo = registrationCodesRepository.getAliveCodeOrNull(request.email)

            if (codeDbo == null || codeDbo.code != request.code) {
                // Record failed attempt
                enterRegistrationCodeAttemptsTracker.recordAttempt(
                    ipAddress = call.getUserIp(), email = request.email, successful = false
                )
                val shouldBlockCode = enterRegistrationCodeAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
                    ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 30
                ) > 30
                if (shouldBlockCode) {
                    registrationCodesRepository.markAsBlocked(request.email)
                }
                return@post call.respond(HttpStatusCode.Unauthorized, "Invalid verification code")
            }
            // Mark code as used
            registrationCodesRepository.markAsUsed(request.email)
            // Record successful verification attempt
            enterRegistrationCodeAttemptsTracker.recordAttempt(
                ipAddress = call.getUserIp(),
                email = request.email,
                successful = true
            )


            // Get user info
            val user = userRepository.findUserByEmail(request.email)
            if (user != null) {
                return@post call.respond(HttpStatusCode.Forbidden, "User already exists")
            }

            // Create token for registration completion
            val token = registrationCompletionTokensRepository.createAndSaveNewToken(request.email)

            // Return the registration completion token
            call.respond(HttpStatusCode.OK, Response(tokenForCompleteRegistration = token.token))
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}