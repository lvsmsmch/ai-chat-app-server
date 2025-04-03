package com.lvsmsmch.aichat.network.routing.auth.login

import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import com.lvsmsmch.aichat.db.repositories.auth.attempts.LoginAttemptsTracker
import com.lvsmsmch.aichat.db.repositories.auth.verification_codes.LoginCodesRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.random.Random

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
        try {
            val request = call.receive<Request>()

            val recentAttempts = loginAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
                ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 1
            )
            if (recentAttempts > 10) return@post call.respond(HttpStatusCode.TooManyRequests)

            val user = userRepository.findUserByEmail(request.email)
            if (user == null || !checkPassword(request.password, user.hashedPassword)) {
                loginAttemptsTracker.recordAttempt(call.getUserIp(), request.email, false)
                delay(Random.nextLong(0, 50))
                return@post call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
            } else {
                loginAttemptsTracker.recordAttempt(call.getUserIp(), request.email, true)
            }

            val codeToBeVerified = if (loginCodesRepository.canGenerateNewCode(request.email)) {
                val newCodeDbo = loginCodesRepository.createNewVerificationCode(request.email)
                emailSender.sendLoginVerificationCode(request.email, newCodeDbo.code)
                loginCodesRepository.save(newCodeDbo)
                newCodeDbo
            } else {
                loginCodesRepository.getAliveCodeOrNull(request.email)
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