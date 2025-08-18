package com.lvsmsmch.aichat.review.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.*
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Route.configureReviewRouting(
    sessionRepository: SessionRepository,
    reviewRepository: ReviewRepository,
    reviewLikeRepository: ReviewLikeRepository,
    characterRepository: CharacterRepository,
    reportRepository: ReportRepository,
    userRepository: UserRepository,
    idGenerator: IdGenerator,
    complexQueryHelper: ComplexQueryHelper,
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
            request.text?.let { validateReviewText(it) }

            val reviewDbo = ReviewDbo(
                id = idGenerator.generateId(EntityType.REVIEW),
                characterId = request.characterId,
                isAnonymous = false,
                createdAt = UtcTimestamp.now().toString(),
                authorId = sessionDbo.userId,
                rating = request.rating,
                text = request.text
            )

            complexQueryHelper.addReview(reviewDbo)

            call.respondSuccess(data = reviewDbo.toReviewDto(mapper))
        }

        /**
         * GET /reviews
         * Получение списка отзывов с пагинацией
         */
        get {
            val sessionDbo = sessionRepository.verifyToken(call)

            val request = GetReviewsRequest(
                characterId = call.request.queryParameters["characterId"]
                    ?: throw BadRequestException("Missing characterId parameter"),
                sortCriteria = call.request.queryParameters["sortCriteria"]?.toIntOrNull() ?: 0,
                cursor = call.request.queryParameters["cursor"],
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10
            )

            require(request.size in 1..100) { "Size must be between 1 and 100" }
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
            val reviews = reviewsToReturn.map { it.toReviewDto(mapper, currentUserId = sessionDbo.userId) }
            val nextCursor = if (hasMore) reviewsToReturn.lastOrNull()?.createdAt else null

            val response = ReviewsResponse(
                reviews = reviews,
                nextCursor = nextCursor
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
                ?: throw BadRequestException("Missing id parameter")
            val request = call.receive<UpdateReviewRequest>()

            val reviewDbo = reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)

            if (reviewDbo.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to modify this review")
            }
            request.rating?.let { validateReviewRating(it) }
            request.text?.let { validateReviewText(it) }

            val oldRating = reviewDbo.rating
            val newRating = request.rating

            val updatedReview = complexQueryHelper.updateReview(
                reviewId = reviewId,
                characterId = reviewDbo.characterId,
                rating = newRating,
                text = request.text,
                oldRating = oldRating
            )

            call.respondSuccess(data = updatedReview.toReviewDto(mapper))
        }

        /**
         * DELETE /reviews/{id}
         * Удаление отзыва
         */
        delete("/{id}") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val reviewId = call.parameters["id"]
                ?: throw BadRequestException("Missing reviewId parameter")

            val reviewDbo = reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)

            if (reviewDbo.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to modify this review")
            }


            complexQueryHelper.deleteReview(reviewId)

            call.respondSuccess()
        }

        /**
         * POST /reviews/{id}/report
         * Жалоба на отзыв
         */
        post("/{id}/report") {
            val currentUserId = sessionRepository.verifyToken(call).userId
            val reviewId = call.parameters["id"]
                ?: throw BadRequestException("Missing reviewId parameter")
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
                ?: throw BadRequestException("Missing reviewId parameter")

            reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)


            if (reviewLikeRepository.isReviewLikedByUser(sessionDbo.userId, reviewId)) {
                throw ForbiddenException("You already liked this review")
            }

            complexQueryHelper.likeReview(reviewId, sessionDbo.userId)

            call.respondSuccess()
        }

        /**
         * POST /reviews/{id}/unlike
         * Убрать лайк с отзыва
         */
        post("/{id}/unlike") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val reviewId = call.parameters["id"]
                ?: throw BadRequestException("Missing reviewId parameter")

            reviewRepository.getReviewById(reviewId)
                ?: throw ReviewNotFoundException(id = reviewId)

            if (!reviewLikeRepository.isReviewLikedByUser(sessionDbo.userId, reviewId)) {
                throw ForbiddenException("You don't have a like on this review")
            }

            complexQueryHelper.unlikeReview(reviewId, sessionDbo.userId)

            call.respondSuccess()
        }

        /**
         * GET /reviews/{id}/likes
         * Получение пользователей, лайкнувших отзыв
         */
        get("/{id}/likes") {
            sessionRepository.verifyToken(call)
            val reviewId = call.parameters["id"]
                ?: throw BadRequestException("Missing reviewId parameter")
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