package com.lvsmsmch.aichat.network.routing.reviews

import com.lvsmsmch.aichat.db.repositories.content.ReviewRepository
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.CharacterRepository
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*

fun Routing.configureDeleteReviewRouting(
    sessionRepository: SessionRepository,
    reviewRepository: ReviewRepository,
    characterRepository: CharacterRepository,
) {
    delete("/characters/{characterId}/reviews/{id}") {
        try {
            val tokenDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@delete call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            val characterId = call.parameters["characterId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing character id")

            val reviewId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing review id")

            characterRepository.getCharacter(characterId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, "Character not found")

            val reviewDbo = reviewRepository.getReviewById(reviewId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, "Review not found")

            if (reviewDbo.characterId != characterId) {
                return@delete call.respond(HttpStatusCode.NotFound, "Review not found for this character")
            }

            if (reviewDbo.publisherId != tokenDbo.userId) {
                return@delete call.respond(HttpStatusCode.Forbidden, "You are not the owner")
            }

            reviewRepository.deleteReviewById(reviewId)

            call.respond(HttpStatusCode.NoContent)

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}