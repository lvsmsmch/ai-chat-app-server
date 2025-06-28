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
import io.ktor.server.request.*
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

        /**
         * POST /reviews
         * Создание нового отзыва
         */
        post {
            val sessionDbo = sessionRepository.verifyToken(call)
            val request = call.receive<CreateReviewRequest>()

            characterRepository.getCharacter(request.characterId)
                ?: throw CharacterNotFoundException(id = request.characterId)

            if (reviewRepository.getReview(userId = sessionDbo.userId, characterId = request.characterId) != null) {
                throw AlreadyReviewedException()
            }

            validateReviewRating(request.rating)
            validateReviewText(request.text)

            val reviewDbo = ReviewDbo(
                id = idGenerator.generateId(EntityType.REVIEW),
                characterId = request.characterId,
                isAnonymous = false,
                createdAt = UtcTimestamp.now(),
                authorId = sessionDbo.userId,
                rating = request.rating,
                text = request.text
            ).also { reviewRepository.addReview(it) }

            characterActivityLogRepository.logActivity(
                activityType = ActivityType.REVIEW_ADDED,
                characterId = request.characterId,
                userId = sessionDbo.userId
            )

            call.respondSuccess(data = reviewDbo.toReviewDto(mapper))
        }

        /**
         * GET /reviews
         * Получение списка отзывов с пагинацией
         */
        get {
            val request = GetReviewsRequest(
                characterId = call.request.queryParameters["characterId"]
                    ?: throw ValidationException("Missing characterId parameter"),
                sortCriteria = call.request.queryParameters["sortCriteria"]?.toIntOrNull() ?: 0,
                cursor = call.request.queryParameters["cursor"],
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10
            )

            require(request.size in 1..50) { "Size must be between 1 and 50" }
            validateReviewSortCriteria(request.sortCriteria)

            val beforeTime = request.cursor?.let { UtcTimestamp.parse(it) }

            val reviewsDbos = reviewRepository.getReviews(
                characterId = request.characterId,
                sortCriteria = request.sortCriteria,
                beforeTime = beforeTime,
                size = request.size + 1 // +1 для проверки hasMore
            )

            val hasMore = reviewsDbos.size > request.size
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

        /**
         * PATCH /reviews/{id}
         * Обновление отзыва
         */
        patch("/{id}") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val reviewId = call.parameters["id"]
                ?: throw ValidationException("Missing id parameter")
            val request = call.receive<UpdateReviewRequest>()

            val reviewDbo = reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)

            if (reviewDbo.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to modify this review")
            }

            validateReviewRating(request.rating)
            validateReviewText(request.text)

            reviewRepository.updateReview(
                id = reviewId,
                rating = request.rating,
                text = request.text
            )

            call.respondSuccess()
        }

        /**
         * DELETE /reviews/{id}
         * Удаление отзыва
         */
        delete("/{id}") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val reviewId = call.parameters["id"]
                ?: throw ValidationException("Missing reviewId parameter")

            val reviewDbo = reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)

            if (reviewDbo.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to modify this review")
            }

            reviewRepository.deleteReviewById(reviewId)

            call.respondSuccess()
        }

        /**
         * POST /reviews/{id}/report
         * Жалоба на отзыв
         */
        post("/{id}/report") {
            val currentUserId = sessionRepository.verifyToken(call).userId
            val reviewId = call.parameters["id"]
                ?: throw ValidationException("Missing reviewId parameter")
            val request = call.receive<ReportReviewRequest>()

            reportRepository.addReport(
                ReportDbo(
                    reportedBy = currentUserId,
                    entityType = ReportEntity.Review.code,
                    entityId = reviewId,
                    reason = request.reason,
                    text = request.text
                )
            )

            call.respondSuccess()
        }

        /**
         * POST /reviews/{id}/like
         * Лайк отзыва
         */
        post("/{id}/like") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val reviewId = call.parameters["id"]
                ?: throw ValidationException("Missing reviewId parameter")

            reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)

            reviewLikeRepository.likeReview(sessionDbo.userId, reviewId)

            call.respondSuccess()
        }

        /**
         * POST /reviews/{id}/unlike
         * Убрать лайк с отзыва
         */
        post("/{id}/unlike") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val reviewId = call.parameters["id"]
                ?: throw ValidationException("Missing reviewId parameter")

            reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)

            reviewLikeRepository.unlikeReview(sessionDbo.userId, reviewId)

            call.respondSuccess()
        }

        /**
         * GET /reviews/{id}/likes
         * Получение пользователей, лайкнувших отзыв
         */
        get("/{id}/likes") {
            sessionRepository.verifyToken(call)
            val reviewId = call.parameters["id"]
                ?: throw ValidationException("Missing reviewId parameter")
            val request = GetReviewLikesRequest(
                limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
            )

            val userIds = reviewLikeRepository.getUsersWhoLiked(reviewId, request.limit)
            val users = userIds.mapNotNull { userRepository.getUserById(it) }
                .map { it.toUserDto(mapper) }

            call.respondSuccess(data = ReviewLikesResponse(users = users))
        }
    }
}