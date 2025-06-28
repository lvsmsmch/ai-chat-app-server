package com.lvsmsmch.aichat._trash.register

import com.lvsmsmch.aichat._trash.attempts.EnterRegistrationCodeAttemptsTracker
import com.lvsmsmch.aichat._trash.single_use_tokens.RegistrationCompletionTokenRepository
import com.lvsmsmch.aichat._trash.verification_codes.RegistrationCodesRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.EmailAlreadyInUseException
import com.lvsmsmch.aichat.utils.TooManyAttemptsException
import com.lvsmsmch.aichat.utils.WrongCodeException
import com.lvsmsmch.aichat.utils.getUserIp
import com.lvsmsmch.aichat.utils.respondSuccess
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
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
        val request = call.receive<Request>()

        val recentAttempts = enterRegistrationCodeAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
            ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 1
        )

        if (recentAttempts > 5) {
            throw TooManyAttemptsException()
        }

        val codeDbo = registrationCodesRepository.getAliveCodeOrNull(request.email)

        if (codeDbo == null || codeDbo.code != request.code) {
            enterRegistrationCodeAttemptsTracker.recordAttempt(
                ipAddress = call.getUserIp(), email = request.email, successful = false
            )
            val shouldBlockCode = enterRegistrationCodeAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
                ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 30
            ) > 30
            if (shouldBlockCode) {
                registrationCodesRepository.markAsBlocked(request.email)
            }
            throw WrongCodeException()
        }
        registrationCodesRepository.markAsUsed(request.email)
        enterRegistrationCodeAttemptsTracker.recordAttempt(
            ipAddress = call.getUserIp(),
            email = request.email,
            successful = true
        )
        val user = userRepository.findUserByEmail(request.email)
        if (user != null) {
            throw EmailAlreadyInUseException()
        }

        val token = registrationCompletionTokensRepository.createAndSaveNewToken(request.email)
        call.respondSuccess(data = Response(tokenForCompleteRegistration = token.token))
    }
}