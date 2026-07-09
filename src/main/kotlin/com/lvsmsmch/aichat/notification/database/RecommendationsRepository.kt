package com.lvsmsmch.aichat.notification.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.mongodb.reactivestreams.client.ClientSession
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

class RecommendationsRepository(
    private val collection: CoroutineCollection<RecommendationsDbo>
) {


    suspend fun ensureIndexes() {
        collection.ensureIndex(ascending(RecommendationsDbo::userId))
        collection.ensureIndex(descending(RecommendationsDbo::createdAt))

        collection.ensureIndex(
            ascending(
                RecommendationsDbo::userId,
                RecommendationsDbo::createdAt
            )
        )
    }

    val databaseEventsFlow = createDatabaseEventsFlow(collection)


    suspend fun insertNotification(
        session: ClientSession? = null,
        notification: RecommendationsDbo
    ) {
        if (session != null) {
            collection.insertOne(session, notification)
        } else {
            collection.insertOne(notification)
        }
    }




    suspend fun getNotificationById(notificationId: String): RecommendationsDbo? {
        return collection.findOneById(notificationId)
    }

    suspend fun getNotificationsAfter(
        userId: String,
        timestamp: UtcTimestamp
    ): List<RecommendationsDbo> {
        val filter = and(
            RecommendationsDbo::userId eq userId,
            RecommendationsDbo::createdAt gt timestamp.toString()
        )

        return collection.find(filter)
            .sort(descending(RecommendationsDbo::createdAt))
            .toList()
    }



    suspend fun deleteNotification(notificationId: String) {
        collection.deleteOneById(notificationId)
    }

    suspend fun deleteNotifications(notificationIds: List<String>) {
        if (notificationIds.isEmpty()) return

        collection.deleteMany(
            RecommendationsDbo::id `in` notificationIds
        )
    }

    suspend fun deleteUserNotifications(userId: String) {
        collection.deleteMany(
            RecommendationsDbo::userId eq userId
        )
    }
}