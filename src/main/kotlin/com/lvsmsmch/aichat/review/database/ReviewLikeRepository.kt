package com.lvsmsmch.aichat.review.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.utils.DbSession
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

class ReviewLikeRepository {

    private fun likeId(userId: String, reviewId: String) = "${userId}_${reviewId}"

    suspend fun likeReview(session: DbSession, userId: String, reviewId: String) {
        dbQuery {
            val id = likeId(userId, reviewId)
            val exists = Tables.ReviewLikes.selectAll()
                .where { Tables.ReviewLikes.id eq id }
                .limit(1)
                .any()
            if (!exists) {
                Tables.ReviewLikes.insert {
                    it.from(ReviewLikeDbo(id = id, userId = userId, reviewId = reviewId))
                }
            }
        }
    }

    suspend fun isReviewLikedByUser(userId: String, reviewId: String): Boolean = dbQuery {
        Tables.ReviewLikes.selectAll()
            .where { Tables.ReviewLikes.id eq likeId(userId, reviewId) }
            .limit(1)
            .any()
    }

    suspend fun getLikesCount(reviewId: String): Int = dbQuery {
        Tables.ReviewLikes.selectAll()
            .where { Tables.ReviewLikes.reviewId eq reviewId }
            .count()
            .toInt()
    }

    suspend fun getUsersWhoLiked(reviewId: String, limit: Int = 10): List<String> = dbQuery {
        Tables.ReviewLikes.selectAll()
            .where { Tables.ReviewLikes.reviewId eq reviewId }
            .orderBy(Tables.ReviewLikes.likedAt to SortOrder.DESC)
            .limit(limit)
            .map { it[Tables.ReviewLikes.userId] }
    }

    suspend fun checkMultipleLikes(userId: String, reviewIds: List<String>): Map<String, Boolean> {
        if (reviewIds.isEmpty()) return emptyMap()
        val liked = dbQuery {
            Tables.ReviewLikes.selectAll()
                .where {
                    (Tables.ReviewLikes.userId eq userId) and
                        (Tables.ReviewLikes.reviewId inList reviewIds)
                }
                .map { it[Tables.ReviewLikes.reviewId] }
                .toSet()
        }
        return reviewIds.associateWith { it in liked }
    }

    suspend fun unlikeReview(session: DbSession, userId: String, reviewId: String) {
        dbQuery {
            Tables.ReviewLikes.deleteWhere { Tables.ReviewLikes.id eq likeId(userId, reviewId) }
        }
    }

    suspend fun removeAllLikesForReview(session: DbSession, reviewId: String) {
        dbQuery { Tables.ReviewLikes.deleteWhere { Tables.ReviewLikes.reviewId eq reviewId } }
    }

    suspend fun removeAllLikesForReviews(session: DbSession, reviewIds: List<String>) {
        if (reviewIds.isEmpty()) return
        dbQuery { Tables.ReviewLikes.deleteWhere { Tables.ReviewLikes.reviewId inList reviewIds } }
    }
}
