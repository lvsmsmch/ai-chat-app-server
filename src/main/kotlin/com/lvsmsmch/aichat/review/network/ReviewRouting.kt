package com.lvsmsmch.aichat.review.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat._common.database.ReportDbo
import com.lvsmsmch.aichat._common.database.ReportEntity
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.ActivityType
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.routing.*

fun Routing.configureReviewRouting(
    sessionRepository: SessionRepository,
    reviewRepository: ReviewRepository,
    reviewLikeRepository: ReviewLikeRepository,
    characterRepository: CharacterRepository,
    characterActivityLogRepository: CharacterActivityLogRepository,
    reportRepository: ReportRepository,
    userRepository: UserRepository,
    idGenerator: IdGenerator,
    mapper: Mapper
) {
    route("/reviews") {

        post {
            val sessionDbo = sessionRepository.verifyToken(call)

            val characterId = call.request.queryParameters["characterId"]
                ?: throw BadRequestException("Missing characterId parameter")

            val rating = call.request.queryParameters["rating"]?.toInt()
                ?: throw BadRequestException("Missing rating parameter")

            val text = call.request.queryParameters["text"] ?: ""

            characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (reviewRepository.getReview(userId = sessionDbo.userId, characterId = characterId) != null) {
                throw AlreadyReviewedException()
            }

            validateReviewRating(rating)
            validateReviewText(text)

            val reviewDbo = ReviewDbo(
                id = idGenerator.generateId(EntityType.REVIEW),
                characterId = characterId,
                isAnonymous = false,
                createdAt = UtcTimestamp.now(),
                authorId = sessionDbo.userId,
                rating = rating,
                text = text
            ).also { reviewRepository.addReview(it) }

            characterActivityLogRepository.logActivity(
                activityType = ActivityType.REVIEW_ADDED,
                characterId = characterId,
                userId = sessionDbo.userId
            )

            call.respondSuccess(data = reviewDbo.toReviewDto(mapper))
        }

        get {
            val characterId = call.request.queryParameters["characterId"]
                ?: throw BadRequestException("Missing characterId parameter")
            val sortCriteria = call.request.queryParameters["sortCriteria"]?.toIntOrNull() ?: 0
            val cursor = call.request.queryParameters["cursor"] // timestamp строка
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10

            require(size in 1..50) { "Size must be between 1 and 50" }
            validateReviewSortCriteria(sortCriteria)

            val beforeTime = cursor?.let { UtcTimestamp.parse(it) }

            val reviewsDbos = reviewRepository.getReviews(
                characterId = characterId,
                sortCriteria = sortCriteria,
                beforeTime = beforeTime,
                size = size + 1 // +1 для проверки hasMore
            )

            val hasMore = reviewsDbos.size > size
            val reviewsToReturn = if (hasMore) reviewsDbos.dropLast(1) else reviewsDbos

            val reviews = reviewsToReturn.map { it.toReviewDto(mapper) }
            val nextCursor = if (hasMore) reviewsToReturn.lastOrNull()?.createdAt?.toString() else null

            val response = ReviewsResponse(
                reviews = reviews,
                nextCursor = nextCursor,
                hasMore = hasMore
            )

            call.respondSuccess(data = response)
        }

        patch("/{id}") {
            val sessionDbo = sessionRepository.verifyToken(call)

            val reviewId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")

            val reviewDbo = reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)

            if (reviewDbo.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to modify this review")
            }

            val rating = call.request.queryParameters["rating"]?.toInt()
                ?: throw BadRequestException("Missing rating parameter")

            val text = call.request.queryParameters["text"] ?: ""

            validateReviewRating(rating)
            validateReviewText(text)

            reviewRepository.updateReview(
                id = reviewId,
                rating = rating,
                text = text
            )

            call.respondSuccess()
        }

        delete("/{id}") {
            val sessionDbo = sessionRepository.verifyToken(call)

            val reviewId = call.parameters["id"]
                ?: throw BadRequestException("Missing reviewId parameter")

            val reviewDbo = reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)

            if (reviewDbo.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to modify this review")
            }

            reviewRepository.deleteReviewById(reviewId)

            call.respondSuccess()
        }

        post("/{id}/report") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val reviewId = call.parameters["id"]
                ?: throw BadRequestException("Missing reviewId parameter")

            val reason = call.request.queryParameters["reason"]
                ?: throw BadRequestException("Missing reason parameter")

            val text = call.request.queryParameters["text"] ?: ""

            reportRepository.addReport(
                ReportDbo(
                    reportedBy = currentUserId,
                    entityType = ReportEntity.Review.code,
                    entityId = reviewId,
                    reason = reason,
                    text = text
                )
            )

            call.respondSuccess()
        }

        post("/{id}/like") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val reviewId = call.parameters["id"] ?: throw BadRequestException("Missing reviewId parameter")

            reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)

            reviewLikeRepository.likeReview(sessionDbo.userId, reviewId)

            call.respondSuccess()
        }

        post("/{id}/unlike") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val reviewId = call.parameters["id"] ?: throw BadRequestException("Missing reviewId parameter")

            reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)

            reviewLikeRepository.unlikeReview(sessionDbo.userId, reviewId)

            call.respondSuccess()
        }

        get("/{id}/likes") {    // not needed
            sessionRepository.verifyToken(call)
            val reviewId = call.parameters["id"] ?: throw BadRequestException("Missing reviewId parameter")
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10

            val userIds = reviewLikeRepository.getUsersWhoLiked(reviewId, limit)
            val users = userIds.mapNotNull { userRepository.getUserById(it) }
                .map { it.toUserDto(mapper) }

            call.respondSuccess(data = users)
        }
    }
}