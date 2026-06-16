package com.lvsmsmch.aichat.notification.network

import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.notification.database.RecommendationsRepository
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Route.configureReviewRouting(
    sessionRepository: SessionRepository,
    recommendationsRepository: RecommendationsRepository,
    reviewRepository: ReviewRepository,
    reviewLikeRepository: ReviewLikeRepository,
    characterRepository: CharacterRepository,
    userRepository: UserRepository,
    followRepository: FollowRepository,
    complexQueryHelper: ComplexQueryHelper,
    mapper: Mapper
) {
    route("/notifications") {

        post("/sync") {
            val sessionDbo = sessionRepository.verifyToken(call)

            val request = call.receive<SyncNotificationsRequest>()

            val recommendationsDbos = recommendationsRepository.getNotificationsAfter(
                userId = sessionDbo.userId,
                timestamp = UtcTimestamp.parse(request.timestamp)
            )

            val recommendationDtos = recommendationsDbos.map { it.toNotificationDto(mapper) }

            val response = SyncNotificationsResponse(
                items = listOf(
                    recommendationDtos,
                ).flatten()
            )

            call.respondSuccess(data = recommendationDtos)
        }

    }
}