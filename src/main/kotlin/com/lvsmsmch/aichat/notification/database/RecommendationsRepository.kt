package com.lvsmsmch.aichat.notification.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toRecommendationsDbo
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

class RecommendationsRepository {

    suspend fun insertNotification(
        session: DbSession? = null,
        notification: RecommendationsDbo,
    ) {
        dbQuery { Tables.Recommendations.insert { it.from(notification) } }
    }

    suspend fun getNotificationById(notificationId: String): RecommendationsDbo? = dbQuery {
        Tables.Recommendations.selectAll()
            .where { Tables.Recommendations.id eq notificationId }
            .limit(1)
            .firstOrNull()
            ?.toRecommendationsDbo()
    }

    suspend fun getNotificationsAfter(
        userId: String,
        timestamp: UtcTimestamp,
    ): List<RecommendationsDbo> = dbQuery {
        Tables.Recommendations.selectAll()
            .where {
                (Tables.Recommendations.userId eq userId) and
                    (Tables.Recommendations.createdAt greater timestamp.toString())
            }
            .orderBy(Tables.Recommendations.createdAt to SortOrder.DESC)
            .map { it.toRecommendationsDbo() }
    }

    suspend fun deleteNotification(notificationId: String) {
        dbQuery { Tables.Recommendations.deleteWhere { Tables.Recommendations.id eq notificationId } }
    }

    suspend fun deleteNotifications(notificationIds: List<String>) {
        if (notificationIds.isEmpty()) return
        dbQuery {
            Tables.Recommendations.deleteWhere { Tables.Recommendations.id inList notificationIds }
        }
    }

    suspend fun deleteUserNotifications(userId: String) {
        dbQuery { Tables.Recommendations.deleteWhere { Tables.Recommendations.userId eq userId } }
    }
}
