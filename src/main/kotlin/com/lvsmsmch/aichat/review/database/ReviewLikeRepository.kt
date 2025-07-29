package com.lvsmsmch.aichat.review.database

import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.mongodb.reactivestreams.client.ClientSession
import kotlinx.coroutines.runBlocking
import org.litote.kmongo.ascending
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.descending
import org.litote.kmongo.eq
import org.litote.kmongo.`in`

class ReviewLikeRepository(
    private val collection: CoroutineCollection<ReviewLikeDbo>
) {

    /**
     * Initialize indexes for the collection
     */

    init {
        initializeIndexes()
    }
    
    private fun initializeIndexes() {
        runBlocking {
            collection.ensureIndex(ascending(ReviewLikeDbo::userId))
            collection.ensureIndex(ascending(ReviewLikeDbo::reviewId))
            collection.ensureIndex(descending(ReviewLikeDbo::likedAt))
        }
    }


    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)


    /**
     * CREATE
     */

    suspend fun likeReview(session: ClientSession, userId: String, reviewId: String) {
        val likeId = "${userId}_${reviewId}"
        val existing = collection.findOneById(likeId)
        
        if (existing == null) {
            collection.insertOne(
                session,
                ReviewLikeDbo(
                    id = likeId,
                    userId = userId,
                    reviewId = reviewId
                )
            )
        }
    }
    


    /**
     * READ
     */


    suspend fun isReviewLikedByUser(userId: String, reviewId: String): Boolean {
        val likeId = "${userId}_${reviewId}"
        return collection.findOneById(likeId) != null
    }


    suspend fun getLikesCount(reviewId: String): Int {
        return collection.countDocuments(ReviewLikeDbo::reviewId eq reviewId).toInt()
    }
    
    suspend fun getUsersWhoLiked(reviewId: String, limit: Int = 10): List<String> {
        return collection.find(ReviewLikeDbo::reviewId eq reviewId)
            .sort(descending(ReviewLikeDbo::likedAt))
            .limit(limit)
            .toList()
            .map { it.userId }
    }
    
    suspend fun checkMultipleLikes(userId: String, reviewIds: List<String>): Map<String, Boolean> {
        val likeIds = reviewIds.map { "${userId}_${it}" }
        val likes = collection.find(ReviewLikeDbo::id `in` likeIds).toList()
        val likedSet = likes.map { it.reviewId }.toSet()
        
        return reviewIds.associateWith { it in likedSet }
    }


    /**
     * UPDATE
     */

    /**
     * DELETE
     */

    suspend fun unlikeReview(session: ClientSession, userId: String, reviewId: String) {
        val likeId = "${userId}_${reviewId}"
        collection.deleteOneById(session, likeId)
    }

    suspend fun removeAllLikesForReview(session: ClientSession, reviewId: String) {
        collection.deleteMany(session, ReviewLikeDbo::reviewId eq reviewId)
    }

    suspend fun removeAllLikesForReviews(session: ClientSession, reviewIds: List<String>) {
        if (reviewIds.isEmpty()) return

        collection.deleteMany(
            session,
            ReviewLikeDbo::reviewId `in` reviewIds
        )
    }
}