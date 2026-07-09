package com.lvsmsmch.aichat.feedback.database

import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

class FeedbackRepository(
    private val collection: CoroutineCollection<FeedbackDbo>
) {


    suspend fun ensureIndexes() {
        collection.ensureIndex(ascending(FeedbackDbo::userId))
        collection.ensureIndex(descending(FeedbackDbo::createdAt))
    }

    suspend fun addFeedback(feedback: FeedbackDbo) {
        collection.insertOne(feedback)
    }

    suspend fun getFeedbacksByUserId(userId: String): List<FeedbackDbo> {
        return collection.find(FeedbackDbo::userId eq userId)
            .sort(descending(FeedbackDbo::createdAt))
            .toList()
    }

    suspend fun getAllFeedbacks(limit: Int = 100): List<FeedbackDbo> {
        return collection.find()
            .sort(descending(FeedbackDbo::createdAt))
            .limit(limit)
            .toList()
    }
}