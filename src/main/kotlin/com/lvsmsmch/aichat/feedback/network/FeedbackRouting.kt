package com.lvsmsmch.aichat.feedback.network

import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.feedback.database.FeedbackDbo
import com.lvsmsmch.aichat.feedback.database.FeedbackRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.configureFeedbackRouting(
    feedbackRepository: FeedbackRepository,
    sessionRepository: SessionRepository
) {

    route("/feedback") {

        /**
         * POST /feedback
         * Отправка фидбека
         */
        post {
            val sessionDbo = sessionRepository.verifyToken(call)
            val request = call.receive<SendFeedbackRequest>()

            require(request.text.isNotBlank()) { "Feedback text cannot be empty" }
            require(request.text.length <= 5000) { "Feedback text is too long (max 5000 characters)" }

            val feedbackDbo = FeedbackDbo(
                id = UUID.randomUUID().toString(),
                userId = sessionDbo.userId,
                text = request.text
            )

            feedbackRepository.addFeedback(feedbackDbo)

            call.respondSuccess()
        }
    }
}