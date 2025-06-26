package com.lvsmsmch.aichat.review.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.bson.conversions.Bson
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection


class ReviewRepository(
    private val collection: CoroutineCollection<ReviewDbo>
) {

    /**
     * Initialize indexes for the collection
     */

    init {
        initializeIndexes()
    }

    private fun initializeIndexes() {
        runBlocking {
            collection.ensureIndex(ascending(ReviewDbo::characterId))
        }
    }

    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    /**
     * CREATE
     */
    suspend fun addReview(reviewDbo: ReviewDbo) {
        collection.insertOne(reviewDbo)
    }

    /**
     * READ
     */
    suspend fun getReviews(
        characterId: String,
        sortCriteria: Int,
        beforeTime: UtcTimestamp?,
        size: Int
    ): List<ReviewDbo> {
        val sort = when (sortCriteria) {
            ReviewSortCriteria.NEWEST.code -> descending(ReviewDbo::createdAt)
            ReviewSortCriteria.OLDEST.code -> ascending(ReviewDbo::createdAt)
            ReviewSortCriteria.HIGHEST_RATING.code -> descending(ReviewDbo::rating, ReviewDbo::createdAt)
            ReviewSortCriteria.LOWEST_RATING.code -> ascending(ReviewDbo::rating, ReviewDbo::createdAt)
            ReviewSortCriteria.MOST_POPULAR.code -> descending(ReviewDbo::likesCount, ReviewDbo::createdAt)
            ReviewSortCriteria.LEAST_POPULAR.code -> ascending(ReviewDbo::likesCount, ReviewDbo::createdAt)
            else -> descending(ReviewDbo::createdAt)
        }

        return collection.find(
            and(
                ReviewDbo::characterId eq characterId,
                if (beforeTime != null) ReviewDbo::createdAt lt beforeTime else EMPTY_BSON
            )
        ).sort(sort)
            .limit(size)
            .toList()
    }

    suspend fun getReviewById(reviewId: String): ReviewDbo? {
        return collection.findOneById(reviewId)
    }

    suspend fun getReview(userId: String, characterId: String): ReviewDbo? {
        return collection.findOne(
            and(
                ReviewDbo::authorId eq userId,
                ReviewDbo::characterId eq characterId
            )
        )
    }

    suspend fun getTotalReviewsCountForCharacter(characterId: String): Int {
        return collection.countDocuments(ReviewDbo::characterId eq characterId).toInt()
    }

    suspend fun getAvgRatingForCharacter(characterId: String): Float {
        val pipeline = listOf(
            match(ReviewDbo::characterId eq characterId),
            group(
                id = null, ReviewDbo::rating avg "averageRating"
            )
        )
        val result = collection.aggregate<Document>(pipeline).first()
        return result?.getDouble("averageRating")?.toFloat() ?: 0f
    }

    /**
     * UPDATE
     */
    suspend fun updateReview(
        id: String,
        rating: Int? = null,
        text: String? = null,
        isAnonymous: Boolean? = null,
    ) {
        getReviewById(id) ?: return

        val updates = mutableListOf<Bson>()
        if (rating != null) {
            updates.add(setValue(ReviewDbo::rating, rating))
        }
        if (text != null) {
            updates.add(setValue(ReviewDbo::text, text))
        }
        if (isAnonymous != null) {
            updates.add(setValue(ReviewDbo::isAnonymous, isAnonymous))
        }
        updates.add(setValue(ReviewDbo::editedAt, UtcTimestamp.now()))

        collection.updateOneById(id, combine(updates))
        // No need to manually emit updates - the changeEventsFlow will handle it
    }

    suspend fun incrementLikesCount(reviewId: String, increment: Int) {
        collection.updateOneById(reviewId, inc(ReviewDbo::likesCount, increment))
    }


    /**
     * DELETE
     */
    suspend fun deleteReview(userId: String, characterId: String) {
        val review = getReview(userId, characterId) ?: return
        deleteReviewById(review.id)
    }

    suspend fun deleteReviewById(reviewId: String) {
        val reviewDbo = getReviewById(reviewId) ?: return
        collection.deleteOneById(reviewId)
        // No need to manually emit updates - the changeEventsFlow will handle it
    }

    suspend fun isReviewOwnedByUser(reviewId: String, userId: String): Boolean {
        val review = getReviewById(reviewId)
        return review?.authorId == userId
    }
}