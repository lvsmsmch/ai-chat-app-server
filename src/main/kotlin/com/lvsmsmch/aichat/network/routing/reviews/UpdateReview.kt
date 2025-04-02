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

fun Routing.configureUpdateReviewRouting(
    sessionRepository: SessionRepository,
    charactersRepository: CharactersRepository,
    reviewsRepository: ReviewsRepository,
) {

    @Serializable
    data class Updates(
        val rating: Int? = null,
        val text: String? = null,
        val isAnonymous: Boolean? = null,
    )

    patch("/characters/{characterId}/reviews/{id}") {
        try {
            val tokenDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@patch call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            val characterId = call.parameters["characterId"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing character id")

            val reviewId = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing review id")

            charactersRepository.getCharacter(characterId)
                ?: return@patch call.respond(HttpStatusCode.NotFound, "Character not found")

            val reviewDbo = reviewsRepository.getReviewById(reviewId)
                ?: return@patch call.respond(HttpStatusCode.NotFound, "Review not found")

            if (reviewDbo.characterId != characterId) {
                return@patch call.respond(HttpStatusCode.NotFound, "Review not found for this character")
            }

            if (reviewDbo.publisherId != tokenDbo.userId) {
                return@patch call.respond(HttpStatusCode.Forbidden, "You are not the owner")
            }

            val updates = call.receive<Updates>()

            if (updates.rating == null && updates.text == null && updates.isAnonymous == null) {
                return@patch call.respond(HttpStatusCode.BadRequest, "No update fields provided")
            }

            if (updates.rating != null && updates.rating !in 1..5) {
                return@patch call.respond(HttpStatusCode.BadRequest, "Rating must be between 1 and 5")
            }

            if (updates.text != null && updates.text.length > 1000) {
                return@patch call.respond(HttpStatusCode.BadRequest, "Text exceeds 1000 characters")
            }

            reviewsRepository.updateReview(
                id = reviewId,
                rating = updates.rating,
                text = updates.text,
                isAnonymous = updates.isAnonymous
            )

            call.respond(HttpStatusCode.OK)

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}