package com.lvsmsmch.aichat.network

import com.lvsmsmch.aichat.db.Database
import com.lvsmsmch.aichat.network.requests.reviews.AddReviewRequest
import com.lvsmsmch.aichat.network.requests.reviews.DeleteReviewRequest
import com.lvsmsmch.aichat.network.requests.reviews.GetReviewsRequest
import com.lvsmsmch.aichat.network.utils.parseRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant

fun Application.configureRouting(database: Database) {
    routing {

        // Add Review Route
        post("/reviews/add") {
            val authHeader = call.request.headers["Authorization"]

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(HttpStatusCode.BadRequest, "Authorization token missing or invalid.")
                return@post
            }

            val authToken = authHeader.removePrefix("Bearer ")
            val sessionDbo = database.getSession(authToken)

            if (sessionDbo == null) {
                call.respond(HttpStatusCode.Unauthorized, "Authorization token expired or invalid.")
                return@post
            }

            if (sessionDbo.expiresAt.instant.isBefore(Instant.now())) {
                call.respond(HttpStatusCode.Unauthorized, "Authorization token expired.")
                return@post
            }

            val request: AddReviewRequest? = call.parseRequest()

            if (request == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request format.")
                return@post
            }

            val addReviewResult = try {
                database.addReview(
                    userId = sessionDbo.userId,
                    characterId = request.characterId,
                    rating = request.rating,
                    text = request.text,
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error adding review.")
                return@post
            }

            if (!addReviewResult) {
                call.respond(HttpStatusCode.InternalServerError, "Error adding review.")
                return@post
            }

            call.respond(HttpStatusCode.Created, "Review added successfully.")
        }

        // Delete Review Route
        post("/reviews/delete") {
            val authHeader = call.request.headers["Authorization"]

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(HttpStatusCode.BadRequest, "Authorization token missing or invalid.")
                return@post
            }

            val authToken = authHeader.removePrefix("Bearer ")
            val sessionDbo = database.getSession(authToken)

            if (sessionDbo == null) {
                call.respond(HttpStatusCode.Unauthorized, "Authorization token expired or invalid.")
                return@post
            }

            if (sessionDbo.expiresAt.instant.isBefore(Instant.now())) {
                call.respond(HttpStatusCode.Unauthorized, "Authorization token expired.")
                return@post
            }

            val request: DeleteReviewRequest? = call.parseRequest()
            if (request == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request format.")
                return@post
            }

            // Check if the review exists and if it's authored by the user
            val userReview = database.getUsersReviewForCharacter(sessionDbo.userId, request.characterId)
            if (userReview == null) {
                call.respond(HttpStatusCode.NotFound, "Review not found or you are not the author.")
                return@post
            }

            val deleteReviewResult = try {
                database.deleteReview(sessionDbo.userId, request.characterId)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error deleting review.")
                return@post
            }

            if (!deleteReviewResult) {
                call.respond(HttpStatusCode.InternalServerError, "Error deleting review.")
                return@post
            }

            call.respond(HttpStatusCode.OK, "Review deleted successfully.")
        }

        // Get All Reviews for a Character Route
        get("/reviews/{characterId}") {
            val authHeader = call.request.headers["Authorization"]

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(HttpStatusCode.BadRequest, "Authorization token missing or invalid.")
                return@get
            }

            val authToken = authHeader.removePrefix("Bearer ")
            val sessionDbo = database.getSession(authToken)

            if (sessionDbo == null) {
                call.respond(HttpStatusCode.Unauthorized, "Authorization token expired or invalid.")
                return@get
            }

            if (sessionDbo.expiresAt.instant.isBefore(Instant.now())) {
                call.respond(HttpStatusCode.Unauthorized, "Authorization token expired.")
                return@get
            }

            val request: GetReviewsRequest? = call.parseRequest()
            if (request == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request format.")
                return@get
            }

            val reviews = try {
                database.getAllReviewsForCharacter(
                    characterId = request.characterId,
                    filter = request.filter,
                    limit = request.limit,
                    skip = request.skip
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error fetching reviews.")
                return@get
            }

            call.respond(HttpStatusCode.OK, reviews)
        }
    }
}
