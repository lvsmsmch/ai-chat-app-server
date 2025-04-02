package com.lvsmsmch.aichat.network.routing.reviews

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.CharactersRepository
import com.lvsmsmch.aichat.db.repositories.content.ReviewsRepository
import com.lvsmsmch.aichat.utils.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureAddReviewRouting(
    sessionRepository: SessionRepository,
    reviewsRepository: ReviewsRepository,
    charactersRepository: CharactersRepository,
) {

    @Serializable
    data class Request(
        val rating: Int,
        val text: String,
        val isAnonymous: Boolean,
    )

    post("/characters/{characterId}/reviews") {
        try {
            val tokenDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@post call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            val request = call.receive<Request>()

            val characterId = call.parameters["characterId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing character id")

            charactersRepository.getCharacter(characterId)
                ?: return@post call.respond(HttpStatusCode.NotFound, "Character not found")

            if (request.rating !in 1..5) {
                return@post call.respond(HttpStatusCode.BadRequest, "Rating must be between 1 and 5")
            }

            if (request.text.length > 1000) {
                return@post call.respond(HttpStatusCode.BadRequest, "Length exceeded 1000 characters")
            }

            if (reviewsRepository.getReview(userId = tokenDbo.userId, characterId = characterId) != null) {
                return@post call.respond(HttpStatusCode.Forbidden, "You already left a review")
            }

            reviewsRepository.addReview(
                userId = tokenDbo.userId,
                characterId = characterId,
                rating = request.rating,
                text = request.text,
                isAnonymous = request.isAnonymous
            )

            call.respond(HttpStatusCode.Created)

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}