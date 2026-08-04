package com.lvsmsmch.aichat.review

import com.lvsmsmch.aichat._common.database.DeletedIdsStatsRepository
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.character.database.ActivityType
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.utils.TransactionHelper

/**
 * Отзывы. HTTP-слой для них сейчас не зарегистрирован (в приложении их заменили
 * комментарии), но сами отзывы живы: на них считается рейтинг персонажа, и
 * каскады удаления их трогают. Логика собрана здесь, чтобы при возврате фичи
 * не пришлось выкапывать её из общего котла.
 */
class ReviewService(
    private val reviewRepository: ReviewRepository,
    private val reviewLikeRepository: ReviewLikeRepository,
    private val characterRepository: CharacterRepository,
    private val characterActivityLogRepository: CharacterActivityLogRepository,
    private val deletedIdsStatsRepository: DeletedIdsStatsRepository,
    private val transactionHelper: TransactionHelper,
) {

    suspend fun addReview(reviewDbo: ReviewDbo) {
        transactionHelper.withTransaction { session ->
            reviewRepository.addReview(session, reviewDbo)
            characterRepository.incrementReviewsCount(session, reviewDbo.characterId, 1)
            characterRepository.updateAvgRating(
                session = session,
                characterId = reviewDbo.characterId,
                newRating = reviewRepository.getAvgRatingForCharacter(session, reviewDbo.characterId)
            )
            characterActivityLogRepository.logActivity(
                session = session,
                activityType = ActivityType.REVIEW_ADDED,
                characterId = reviewDbo.characterId,
                userId = reviewDbo.authorId
            )
        }
    }

    suspend fun updateReview(
        reviewId: String,
        characterId: String,
        rating: Int?,
        text: String?,
        oldRating: Int,
    ): ReviewDbo {
        return transactionHelper.withTransaction { session ->
            reviewRepository.updateReview(
                session = session,
                id = reviewId,
                rating = rating,
                text = text
            )
            if (oldRating != rating) {
                characterRepository.updateAvgRating(
                    session = session,
                    characterId = characterId,
                    newRating = reviewRepository.getAvgRatingForCharacter(session, characterId)
                )
            }

            return@withTransaction reviewRepository.getReviewById(reviewId)!!
        }
    }

    suspend fun deleteReview(reviewId: String) {
        transactionHelper.withTransaction { session ->
            val review = reviewRepository.getReviewById(reviewId)!!
            val characterId = review.characterId
            reviewRepository.deleteReviewById(session, reviewId)
            deletedIdsStatsRepository.entityWasDeleted(session, EntityType.REVIEW, reviewId)
            characterRepository.incrementReviewsCount(session, characterId, -1)
            reviewLikeRepository.removeAllLikesForReview(session, reviewId)
            characterRepository.updateAvgRating(
                session = session,
                characterId = characterId,
                newRating = reviewRepository.getAvgRatingForCharacter(session, characterId)
            )
        }
    }

    suspend fun likeReview(reviewId: String, userId: String) {
        transactionHelper.withTransaction { session ->
            reviewLikeRepository.likeReview(session, userId, reviewId)
            reviewRepository.incrementLikesCount(session, reviewId, 1)
        }
    }

    suspend fun unlikeReview(reviewId: String, userId: String) {
        transactionHelper.withTransaction { session ->
            reviewLikeRepository.unlikeReview(session, userId, reviewId)
            reviewRepository.incrementLikesCount(session, reviewId, -1)
        }
    }
}
