package com.lvsmsmch.aichat.feedback.database

import kotlinx.coroutines.runBlocking
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

class FeedbackRepository(
    private val collection: CoroutineCollection<FeedbackDbo>
) {

    init {
        initializeIndexes()
    }

    private fun initializeIndexes() {
        runBlocking {
            collection.ensureIndex(ascending(FeedbackDbo::userId))
            collection.ensureIndex(descending(FeedbackDbo::createdAt))
        }
    }

    /**
     * Сохранение нового фидбека
     */
    suspend fun addFeedback(feedback: FeedbackDbo) {
        collection.insertOne(feedback)
    }

    /**
     * Получение всех фидбеков пользователя
     */
    suspend fun getFeedbacksByUserId(userId: String): List<FeedbackDbo> {
        return collection.find(FeedbackDbo::userId eq userId)
            .sort(descending(FeedbackDbo::createdAt))
            .toList()
    }

    /**
     * Получение всех фидбеков (для админки)
     */
    suspend fun getAllFeedbacks(limit: Int = 100): List<FeedbackDbo> {
        return collection.find()
            .sort(descending(FeedbackDbo::createdAt))
            .limit(limit)
            .toList()
    }
}