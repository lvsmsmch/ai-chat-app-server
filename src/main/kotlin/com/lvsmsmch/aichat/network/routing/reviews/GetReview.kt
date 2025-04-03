package com.lvsmsmch.aichat.network.routing.reviews

import com.lvsmsmch.aichat.db.repositories.content.CharacterRepository
import com.lvsmsmch.aichat.db.repositories.content.ReviewRepository
import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import com.lvsmsmch.aichat.network.dto_objects.ReviewDto
import com.lvsmsmch.aichat.utils.toReviewDto
import com.lvsmsmch.aichat.utils.toUserDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureGetReviewRouting(
    reviewRepository: ReviewRepository,
    characterRepository: CharacterRepository,
    userRepository: UserRepository,
) {
    @Serializable
    data class Response(
        val review: ReviewDto
    )

    get("/characters/{characterId}/reviews/{id}") {
        try {
            val characterId = call.parameters["characterId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing character ID")

            val reviewId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing review ID")

            characterRepository.getCharacter(characterId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Character not found")

            val reviewDbo = reviewRepository.getReviewById(reviewId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Review not found")

            if (reviewDbo.characterId != characterId) {
                return@get call.respond(HttpStatusCode.NotFound, "Review not found for this character")
            }

            val userDto = if (!reviewDbo.isAnonymous) {
                val userDbo = userRepository.getUserById(reviewDbo.publisherId)
                    ?: return@get call.respond(HttpStatusCode.InternalServerError, "User information not found")
                userDbo.toUserDto()
            } else {
                null
            }

            val reviewDto = reviewDbo.toReviewDto(userDto)
            call.respond(HttpStatusCode.OK, Response(reviewDto))

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}