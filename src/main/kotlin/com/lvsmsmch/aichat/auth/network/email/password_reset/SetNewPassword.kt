package com.lvsmsmch.aichat.auth.network.email.password_reset

import com.lvsmsmch.aichat.auth.database.tokens.single_use_tokens.SetNewPasswordTokenRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.UserWithThisEmailNotFoundException
import com.lvsmsmch.aichat.utils.hashPassword
import com.lvsmsmch.aichat.utils.respondSuccess
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Routing.configureSetNewPasswordRouting(
    userRepository: UserRepository,
    setNewPasswordTokenRepository: SetNewPasswordTokenRepository,
) {

    @Serializable
    data class Request(
        val password: String
    )

    post("/auth/reset-password") {
        val request = call.receive<Request>()

        val tokenDbo = setNewPasswordTokenRepository.verifyToken(call)

        val userDbo = userRepository.getUserById(tokenDbo.userId)
            ?: throw UserWithThisEmailNotFoundException()

        userRepository.updateUser(userId = userDbo.id, hashedPassword = hashPassword(request.password))
        setNewPasswordTokenRepository.markAsUsed(tokenDbo.token)
        call.respondSuccess()
    }
}