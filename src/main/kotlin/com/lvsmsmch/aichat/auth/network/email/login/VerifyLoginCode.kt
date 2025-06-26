package com.lvsmsmch.aichat.auth.network.email.login

import com.lvsmsmch.aichat.auth.database.attempts.EnterLoginCodeAttemptsTracker
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.auth.database.verification_codes.LoginCodesRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.TooManyAttemptsException
import com.lvsmsmch.aichat.utils.UserWithThisEmailNotFoundException
import com.lvsmsmch.aichat.utils.WrongCodeException
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Routing.configureVerifyLoginCodeRouting(
    userRepository: UserRepository,
    enterLoginCodeAttemptsTracker: EnterLoginCodeAttemptsTracker,
    loginCodesRepository: LoginCodesRepository,
    sessionRepository: SessionRepository,
    mapper: Mapper
) {

    @Serializable
    data class Request(
        val email: String,
        val code: Int
    )

    post("/auth/verify-login-code") {
//        val request = call.receive<Request>()
//
//        val tooManyRequests = enterLoginCodeAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
//            ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 1
//        ) > 5
//        if (tooManyRequests) {
//            throw TooManyAttemptsException()
//        }
//
//        // Validate verification code
//        val codeDbo = loginCodesRepository.getAliveCodeOrNull(request.email)
//
//        if (codeDbo == null || codeDbo.code != request.code) {
//            enterLoginCodeAttemptsTracker.recordAttempt(
//                ipAddress = call.getUserIp(), email = request.email, successful = false
//            )
//            val shouldBlockCode = enterLoginCodeAttemptsTracker.getRecentFailedAttemptsByIpAndEmail(
//                ipAddress = call.getUserIp(), email = request.email, periodInMinutes = 30
//            ) > 30
//            if (shouldBlockCode) {
//                loginCodesRepository.markAsBlocked(request.email)
//            }
//            throw WrongCodeException()
//        }
//
//        loginCodesRepository.markAsUsed(request.email)
//
//        enterLoginCodeAttemptsTracker.recordAttempt(
//            ipAddress = call.getUserIp(),
//            email = request.email,
//            successful = true
//        )
//
//        val user = userRepository.findUserByEmail(request.email)
//            ?: throw UserWithThisEmailNotFoundException()
//
//        val sessionDbo = sessionRepository.createSession(user.id, call.getUserIp())
//        call.respondSuccess(
//            SuccessfulLoginResponse(
//                sessionToken = sessionDbo.token,
//                myProfileDto = user.toMyProfileDto(mapper)
//            )
//        )
    }
}