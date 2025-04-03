package com.lvsmsmch.aichat.network.routing.characters

import com.lvsmsmch.aichat.db.repositories.content.CharacterRepository
import com.lvsmsmch.aichat.db.repositories.content.ReviewFilter
import com.lvsmsmch.aichat.db.repositories.content.ReviewRepository
import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import com.lvsmsmch.aichat.network.dto_objects.CharacterDetailsDto
import com.lvsmsmch.aichat.utils.toCharacterDetailsDto
import com.lvsmsmch.aichat.utils.toReviewDto
import com.lvsmsmch.aichat.utils.toUserDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureGetCharacterDetailsRouting(
    characterRepository: CharacterRepository,
    reviewRepository: ReviewRepository,
    userRepository: UserRepository
) {

    @Serializable
    data class Response(
        val character: CharacterDetailsDto
    )

    get("/characters/{id}/details") {
        try {
            val characterId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing character id")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Character not found")

            val publisherDto = userRepository.getUserById(characterDbo.publisherId)?.toUserDto()

            val recentReviewsDto = reviewRepository.getReviews(
                characterId = characterId,
                filter = ReviewFilter.NEWEST.code,
                limit = 3,
                skip = 0
            ).map { reviewDbo ->
                val reviewPublisherDto = userRepository.getUserById(reviewDbo.publisherId)?.toUserDto()
                reviewDbo.toReviewDto(reviewPublisherDto)
            }

            val characterDetailsDto = characterDbo.toCharacterDetailsDto(
                publisher = publisherDto,
                recentReviews = recentReviewsDto,
            )

            call.respond(HttpStatusCode.OK, Response(characterDetailsDto))

        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}