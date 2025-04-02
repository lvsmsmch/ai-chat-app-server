package com.lvsmsmch.aichat.network.routing._common

import com.lvsmsmch.aichat.db.repositories.content.UsersRepository
import com.lvsmsmch.aichat.db.repositories._trash.code_trackings.CodeTrackingsBaseRepository
import com.lvsmsmch.aichat.utils.postDefault
import com.lvsmsmch.aichat.utils.generateVerificationCode
import com.lvsmsmch.aichat.utils.sendVerificationCode
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Routing.configureSendCodeToEmailRouting(
    postAddress: String,
    usersRepository: UsersRepository,
    codeTrackingsBaseRepository: CodeTrackingsBaseRepository,
    requireEmailToBeRegistered: Boolean = false,
    requireEmailToBeNotRegistered: Boolean = false,
) {

    @Serializable
    data class Request(
        val email: String
    )

    postDefault<Request>(postAddress) { request ->
        if (requireEmailToBeRegistered && usersRepository.findUserByEmail(request.email) == null) {
            return@postDefault call.respond(HttpStatusCode.NotFound, "User with this email not found")
        }

        if (requireEmailToBeNotRegistered && usersRepository.findUserByEmail(request.email) != null) {
            return@postDefault call.respond(HttpStatusCode.Conflict, "User with this email already exists")
        }

        val codeTrackingDbo = codeTrackingsBaseRepository.getOrCreate(request.email)

        if (codeTrackingDbo.canGenerateNewCode()) {
            val code = generateVerificationCode().also { sendVerificationCode(request.email, it) }
            codeTrackingsBaseRepository.handleNewCodeWasSent(email = request.email, code = code)
            return@postDefault call.respond(HttpStatusCode.OK)
        }

        if (codeTrackingDbo.currentCode != null) {
            return@postDefault call.respond(HttpStatusCode.OK)
        }

        return@postDefault call.respond(
            HttpStatusCode.Forbidden,
            "This email is locked out until ${codeTrackingDbo.generatingNewCodesBlockedUntil}"
        )
    }
}