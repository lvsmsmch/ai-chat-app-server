package com.lvsmsmch.aichat.feedback.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toFeedbackDbo
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

class FeedbackRepository {

    suspend fun addFeedback(feedback: FeedbackDbo) {
        dbQuery { Tables.Feedbacks.insert { it.from(feedback) } }
    }

    suspend fun getFeedbacksByUserId(userId: String): List<FeedbackDbo> = dbQuery {
        Tables.Feedbacks.selectAll()
            .where { Tables.Feedbacks.userId eq userId }
            .orderBy(Tables.Feedbacks.createdAt to SortOrder.DESC)
            .map { it.toFeedbackDbo() }
    }

    suspend fun getAllFeedbacks(limit: Int = 100): List<FeedbackDbo> = dbQuery {
        Tables.Feedbacks.selectAll()
            .orderBy(Tables.Feedbacks.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toFeedbackDbo() }
    }
}
