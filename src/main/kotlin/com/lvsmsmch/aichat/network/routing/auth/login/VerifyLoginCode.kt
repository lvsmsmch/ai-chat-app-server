package com.lvsmsmch.aichat.network.routing.auth.login

import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import com.lvsmsmch.aichat.db.repositories.auth.attempts.EnterLoginCodeAttemptsTracker
import com.lvsmsmch.aichat.db.repositories.auth.verification_codes.LoginCodesRepository
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.utils.getUserIp
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureVerifyLoginCodeRouting(
    userRepository: UserRepository,
    enterLoginCodeAttemptsTracker: EnterLoginCodeAttemptsTracker,
    loginCodesRepository: LoginCodesRepository,
    sessionRepository: SessionRepository
) {

    @Serializable
    data class Request(
        val email: String,
        val code: Int
    )

    @Serializable
    data class Response(
        val sessionToken: String
    )

    post("/auth/verify-login-code") {
        try {
            // Parse request
            val request = call.receive<Request>()

            val tooManyRequests = enterLoginCodeAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
                ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 1
            ) > 5
            if (tooManyRequests) {
                return@post call.respond(HttpStatusCode.TooManyRequests)
            }


            // Validate verification code
            val codeDbo = loginCodesRepository.getAliveCodeOrNull(request.email)

            if (codeDbo == null || codeDbo.code != request.code) {
                // Record failed attempt
                enterLoginCodeAttemptsTracker.recordAttempt(
                    ipAddress = call.getUserIp(), email = request.email, successful = false
                )
                val shouldBlockCode = enterLoginCodeAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
                    ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 30
                ) > 30
                if (shouldBlockCode) {
                    loginCodesRepository.markAsBlocked(request.email)
                }
                return@post call.respond(HttpStatusCode.Unauthorized, "Invalid verification code")
            }
            // Mark code as used
            loginCodesRepository.markAsUsed(request.email)

            // Record successful verification attempt
            enterLoginCodeAttemptsTracker.recordAttempt(
                ipAddress = call.getUserIp(),
                email = request.email,
                successful = true
            )


            // Get user info
            val user = userRepository.findUserByEmail(request.email)
                ?: return@post call.respond(HttpStatusCode.NotFound, "User not found")

            // Create a new session
            val sessionDbo = sessionRepository.createSession(user.id, call.getUserIp())

            // Return the session token
            call.respond(HttpStatusCode.OK, Response(sessionToken = sessionDbo.token))
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}