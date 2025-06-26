package com.lvsmsmch.aichat.auth.network.email.register

import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.auth.database.tokens.single_use_tokens.RegistrationCompletionTokenRepository
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Routing.configureCompleteRegistrationRouting(
    userRepository: UserRepository,
    registrationCompletionTokensRepository: RegistrationCompletionTokenRepository,
    sessionRepository: SessionRepository,
    mapper: Mapper
) {

    @Serializable
    data class Request(
        val password: String,
        val username: String,
        val displayName: String,
        val isAgreedToNews: Boolean,
    )

    @Serializable
    data class Response(
        val sessionToken: String
    )

    post("/auth/complete-registration") {
//        val tokenDbo = registrationCompletionTokensRepository.verifyToken(call)
//
//        val user = userRepository.findUserByEmail(tokenDbo.email)
//        if (user != null) {
//            throw EmailAlreadyInUseException()
//        }
//
//        val request = call.receive<Request>()
//
//        validateUserPassword(request.password)
//        validateUserUsername(request.username)
//        validateUserName(request.displayName)
//
//        val newUserDbo = UserDbo(
//            email = tokenDbo.email,
//            hashedPassword = hashPassword(request.password),
//            username = request.username,
//            name = request.displayName,
//        )
//
//        userRepository.addUser(newUserDbo)
//        val sessionDbo = sessionRepository.createSession(newUserDbo.id, call.getUserIp())
//        registrationCompletionTokensRepository.markAsUsed(tokenDbo.token)
//        call.respondSuccess(
//            SuccessfulLoginResponse(
//                sessionToken = sessionDbo.token,
//                myProfileDto = newUserDbo.toMyProfileDto(mapper)
//            )
//        )
    }
}