package com.lvsmsmch.aichat.network.routing._common

import com.lvsmsmch.aichat.db.repositories.session.single_use.SingleUseAuthTokensRepository
import com.lvsmsmch.aichat.db.repositories._trash.code_trackings.CodeTrackingsBaseRepository
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.postDefault
import com.lvsmsmch.aichat.utils.generateToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Routing.configureVerifyCodeFromEmailRouting(
    postAddress: String,
    codeTrackingsBaseRepository: CodeTrackingsBaseRepository,
    singleUseAuthTokensRepository: SingleUseAuthTokensRepository,
) {

    @Serializable
    data class Request(
        val email: String,
        val code: Int
    )

    @Serializable
    data class Response(
        val authToken: String
    )

    @Serializable
    data class MismatchResponse(
        val attemptsLeft: Int
    )

    postDefault<Request>(postAddress) { request ->
        val codeTracking = codeTrackingsBaseRepository.getOrCreate(request.email)
        val currentCode = codeTracking.currentCode ?: return@postDefault call.respond(
            HttpStatusCode.Forbidden,
            "There is no confirmation code for this email. Please, start procedure from the beginning."
        )

        // User entered correct code.
        if (request.code == currentCode) {
            codeTrackingsBaseRepository.handleIncorrectCodeWasEntered(request.email)
            val token = generateToken()
            val tokenExpiresAt = UtcTimestamp.now().addMinutes(60)
            singleUseAuthTokensRepository.addAuthToken(
                token = token,
                expiresAt = tokenExpiresAt,
                email = request.email
            )
            return@postDefault call.respond(HttpStatusCode.OK, Response(authToken = token))
        }

        // User entered incorrect code, but it is the expired one.
        if (codeTrackingsBaseRepository.isCodeInExpiredList(request.code, request.email)) {
            return@postDefault call.respond(HttpStatusCode.Forbidden, "This code is expired.")
        }

        // User entered incorrect code.
        val failedAttempts = codeTrackingsBaseRepository.getOrCreate(request.email).currentCodeFailedAttempts
        codeTrackingsBaseRepository.handleIncorrectCodeWasEntered(request.email)
        call.respond(HttpStatusCode.NotAcceptable, MismatchResponse(3 - (failedAttempts + 1)))
    }
}