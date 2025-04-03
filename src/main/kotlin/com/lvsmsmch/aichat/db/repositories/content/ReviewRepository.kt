package com.lvsmsmch.aichat.db.repositories.content

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

enum class ReviewFilter(val code: Int) {
    NEWEST(0),
    OLDEST(1),
    HIGHEST_RATING(2),
    LOWEST_RATING(3),
}

@Serializable
data class ReviewDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val characterId: String,
    val isAnonymous: Boolean,
    val publishedAt: UtcTimestamp,
    val publisherId: String,
    val rating: Int,
    val text: String,
    val editedAt: UtcTimestamp? = null
)

class ReviewRepository(
    private val collection: CoroutineCollection<ReviewDbo>
) {

    suspend fun getReviews(characterId: String, filter: Int, limit: Int, skip: Int): List<ReviewDbo> {
        val sortCriteria = when (filter) {
            ReviewFilter.NEWEST.code -> descending(ReviewDbo::publishedAt)
            ReviewFilter.OLDEST.code -> ascending(ReviewDbo::publishedAt)
            ReviewFilter.HIGHEST_RATING.code -> descending(ReviewDbo::rating)
            ReviewFilter.LOWEST_RATING.code -> ascending(ReviewDbo::rating)
            else -> descending(ReviewDbo::publishedAt)
        }

        return collection.find(ReviewDbo::characterId eq characterId)
            .sort(sortCriteria)
            .skip(skip)
            .limit(limit)
            .toList()
    }

    suspend fun getReviewById(reviewId: String): ReviewDbo? {
        return collection.findOneById(reviewId)
    }

    suspend fun getReview(userId: String, characterId: String): ReviewDbo? {
        return collection.findOne(
            and(
                ReviewDbo::publisherId eq userId,
                ReviewDbo::characterId eq characterId
            )
        )
    }

    suspend fun addReview(
        userId: String,
        characterId: String,
        rating: Int,
        text: String,
        isAnonymous: Boolean,
    ): Boolean {
        val newReview = ReviewDbo(
            characterId = characterId,
            isAnonymous = isAnonymous,
            publishedAt = UtcTimestamp.now(),
            publisherId = userId,
            rating = rating,
            text = text
        )

        val insertResult = collection.insertOne(newReview)
        return insertResult.wasAcknowledged()
    }

    suspend fun updateReview(
        id: String,
        rating: Int? = null,
        text: String? = null,
        isAnonymous: Boolean? = null,
    ): Boolean {
        val updates = mutableListOf<Bson>()

        if (rating != null) { updates.add(setValue(ReviewDbo::rating, rating))}
        if (text != null) { updates.add(setValue(ReviewDbo::text, text))}
        if (isAnonymous != null) { updates.add(setValue(ReviewDbo::isAnonymous, isAnonymous))}
        updates.add(setValue(ReviewDbo::editedAt, UtcTimestamp.now()))

        val updateResult = collection.updateOneById(id, combine(updates))
        return updateResult.modifiedCount > 0
    }

    suspend fun isReviewOwnedByUser(reviewId: String, userId: String): Boolean {
        val review = getReviewById(reviewId)
        return review?.publisherId == userId
    }

    suspend fun deleteReview(userId: String, characterId: String): Boolean {
        val review = getReview(userId, characterId) ?: return false
        val deleteResult = collection.deleteOneById(review.id)
        return deleteResult.deletedCount > 0
    }

    suspend fun deleteReviewById(reviewId: String): Boolean {
        val deleteResult = collection.deleteOneById(reviewId)
        return deleteResult.deletedCount > 0
    }
}