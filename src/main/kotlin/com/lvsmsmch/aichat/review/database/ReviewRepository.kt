package com.lvsmsmch.aichat.review.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.mongodb.reactivestreams.client.ClientSession
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
            collection.ensureIndex(
                ascending(
                    ReviewDbo::characterId,
                    ReviewDbo::createdAt
                )
            )

            collection.ensureIndex(
                ascending(
                    ReviewDbo::characterId,
                    ReviewDbo::rating
                )
            )

            collection.ensureIndex(
                ascending(
                    ReviewDbo::characterId,
                    ReviewDbo::likesCount
                )
            )

            collection.ensureIndex(
                ascending(
                    ReviewDbo::authorId,
                    ReviewDbo::characterId
                )
            )

            collection.ensureIndex(ascending(ReviewDbo::authorId))
        }
    }

    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    /**
     * CREATE
     */
    suspend fun addReview(session: ClientSession, reviewDbo: ReviewDbo) {
        collection.insertOne(session, reviewDbo)
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
                if (beforeTime != null) ReviewDbo::createdAt lt beforeTime.toString() else EMPTY_BSON
            )
        ).sort(sort)
            .limit(size)
            .toList()
    }

    suspend fun getReviewById(reviewId: String): ReviewDbo? {
        return collection.findOneById(reviewId)
    }

    suspend fun getReviewIdsByUserId(session: ClientSession, userId: String): List<String> {
        return collection.find(
            session,
            ReviewDbo::authorId eq userId
        ).toList().map { it.id }
    }

    suspend fun getReviewIdsByCharacterId(session: ClientSession, characterId: String): List<String> {
        return collection.find(
            session,
            ReviewDbo::characterId eq characterId
        ).toList().map { it.id }
    }

    suspend fun getReviewIdsByCharacterIds(session: ClientSession, characterIds: List<String>): List<String> {
        if (characterIds.isEmpty()) return emptyList()

        return collection.find(
            session,
            ReviewDbo::characterId `in` characterIds
        ).toList().map { it.id }
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

    suspend fun getAvgRatingForCharacter(session: ClientSession, characterId: String): Float {
        val reviews = collection.find(session, ReviewDbo::characterId eq characterId).toList()
        if (reviews.isEmpty()) return 0f

        val totalRating = reviews.sumOf { it.rating }
        return totalRating.toFloat() / reviews.size
    }

    /**
     * UPDATE
     */
    suspend fun updateReview(
        session: ClientSession,
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
        updates.add(setValue(ReviewDbo::editedAt, UtcTimestamp.now().toString()))

        collection.updateOneById(session, id, combine(updates))
        // No need to manually emit updates - the changeEventsFlow will handle it
    }

    suspend fun incrementLikesCount(session: ClientSession, reviewId: String, increment: Int) {
        collection.updateOneById(session, reviewId, inc(ReviewDbo::likesCount, increment))
    }


    /**
     * DELETE
     */

    suspend fun deleteReviewById(session: ClientSession, reviewId: String) {
        collection.deleteOneById(session, reviewId)
    }

    suspend fun deleteReviewsByIds(session: ClientSession, reviewIds: List<String>) {
        collection.deleteMany(
            session,
            ReviewDbo::id `in` reviewIds
        )
    }

    suspend fun deleteAllReviewsByUserId(session: ClientSession, userId: String) {
        collection.deleteMany(ReviewDbo::authorId eq userId)
    }

    suspend fun deleteAllReviewsByCharacterId(session: ClientSession, characterId: String) {
        collection.deleteMany(ReviewDbo::characterId eq characterId)
    }

    suspend fun isReviewOwnedByUser(reviewId: String, userId: String): Boolean {
        val review = getReviewById(reviewId)
        return review?.authorId == userId
    }
}