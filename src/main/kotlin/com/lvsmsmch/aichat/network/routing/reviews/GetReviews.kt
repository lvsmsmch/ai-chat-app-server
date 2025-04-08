package com.lvsmsmch.aichat.network.routing.reviews

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.toReviewDto
import com.lvsmsmch.aichat.db.repositories.content.ReviewRepository
import com.lvsmsmch.aichat.network.dto_objects.ReviewDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureGetReviewsRouting(
    reviewRepository: ReviewRepository,
    mapper: Mapper,
) {
    @Serializable
    data class Response(
        val reviews: List<ReviewDto>
    )

    get("/characters/{characterId}/reviews") {
        try {
            val characterId = call.parameters["characterId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing character ID")

            val filter = call.request.queryParameters["filter"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
            val skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0

            val reviewsDbo = reviewRepository.getReviews(characterId, filter, limit, skip)
            val reviewsDto = reviewsDbo.map { it.toReviewDto(mapper) }
            call.respond(HttpStatusCode.OK, Response(reviewsDto))
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}