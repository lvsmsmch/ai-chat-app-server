package com.lvsmsmch.aichat.review.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toReviewDbo
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class ReviewRepository {

    suspend fun addReview(session: DbSession, reviewDbo: ReviewDbo) {
        dbQuery { Tables.Reviews.insert { it.from(reviewDbo) } }
    }

    suspend fun getReviews(
        characterId: String,
        sortCriteria: Int,
        beforeTime: UtcTimestamp?,
        size: Int,
    ): List<ReviewDbo> = dbQuery {
        val order: Array<Pair<Column<*>, SortOrder>> = when (sortCriteria) {
            ReviewSortCriteria.OLDEST.code -> arrayOf(Tables.Reviews.createdAt to SortOrder.ASC)
            ReviewSortCriteria.HIGHEST_RATING.code -> arrayOf(
                Tables.Reviews.rating to SortOrder.DESC,
                Tables.Reviews.createdAt to SortOrder.DESC,
            )
            ReviewSortCriteria.LOWEST_RATING.code -> arrayOf(
                Tables.Reviews.rating to SortOrder.ASC,
                Tables.Reviews.createdAt to SortOrder.ASC,
            )
            ReviewSortCriteria.MOST_POPULAR.code -> arrayOf(
                Tables.Reviews.likesCount to SortOrder.DESC,
                Tables.Reviews.createdAt to SortOrder.DESC,
            )
            ReviewSortCriteria.LEAST_POPULAR.code -> arrayOf(
                Tables.Reviews.likesCount to SortOrder.ASC,
                Tables.Reviews.createdAt to SortOrder.ASC,
            )
            else -> arrayOf(Tables.Reviews.createdAt to SortOrder.DESC)
        }
        Tables.Reviews.selectAll()
            .where {
                val base = Tables.Reviews.characterId eq characterId
                if (beforeTime == null) base
                else base and (Tables.Reviews.createdAt less beforeTime.toString())
            }
            .orderBy(*order)
            .limit(size)
            .map { it.toReviewDbo() }
    }

    suspend fun getReviewById(reviewId: String): ReviewDbo? = dbQuery {
        Tables.Reviews.selectAll()
            .where { Tables.Reviews.id eq reviewId }
            .limit(1)
            .firstOrNull()
            ?.toReviewDbo()
    }

    suspend fun getReviewIdsByUserId(session: DbSession, userId: String): List<String> = dbQuery {
        Tables.Reviews.selectAll()
            .where { Tables.Reviews.authorId eq userId }
            .map { it[Tables.Reviews.id] }
    }

    suspend fun getReviewIdsByCharacterId(session: DbSession, characterId: String): List<String> =
        dbQuery {
            Tables.Reviews.selectAll()
                .where { Tables.Reviews.characterId eq characterId }
                .map { it[Tables.Reviews.id] }
        }

    suspend fun getReviewIdsByCharacterIds(
        session: DbSession,
        characterIds: List<String>,
    ): List<String> {
        if (characterIds.isEmpty()) return emptyList()
        return dbQuery {
            Tables.Reviews.selectAll()
                .where { Tables.Reviews.characterId inList characterIds }
                .map { it[Tables.Reviews.id] }
        }
    }

    suspend fun getReview(userId: String, characterId: String): ReviewDbo? = dbQuery {
        Tables.Reviews.selectAll()
            .where {
                (Tables.Reviews.authorId eq userId) and (Tables.Reviews.characterId eq characterId)
            }
            .limit(1)
            .firstOrNull()
            ?.toReviewDbo()
    }

    suspend fun getTotalReviewsCountForCharacter(characterId: String): Int = dbQuery {
        Tables.Reviews.selectAll()
            .where { Tables.Reviews.characterId eq characterId }
            .count()
            .toInt()
    }

    suspend fun getAvgRatingForCharacter(session: DbSession, characterId: String): Float = dbQuery {
        val ratings = Tables.Reviews.selectAll()
            .where { Tables.Reviews.characterId eq characterId }
            .map { it[Tables.Reviews.rating] }
        if (ratings.isEmpty()) 0f else ratings.sum().toFloat() / ratings.size
    }

    suspend fun updateReview(
        session: DbSession,
        id: String,
        rating: Int? = null,
        text: String? = null,
        isAnonymous: Boolean? = null,
    ) {
        dbQuery {
            Tables.Reviews.update({ Tables.Reviews.id eq id }) { statement ->
                rating?.let { statement[Tables.Reviews.rating] = it }
                text?.let { statement[Tables.Reviews.text] = it }
                isAnonymous?.let { statement[Tables.Reviews.isAnonymous] = it }
                statement[Tables.Reviews.editedAt] = UtcTimestamp.now().toString()
            }
        }
    }

    suspend fun incrementLikesCount(session: DbSession, reviewId: String, increment: Int) {
        dbQuery {
            Tables.Reviews.update({ Tables.Reviews.id eq reviewId }) {
                it[Tables.Reviews.likesCount] = Tables.Reviews.likesCount plus increment
            }
        }
    }

    suspend fun deleteReviewById(session: DbSession, reviewId: String) {
        dbQuery { Tables.Reviews.deleteWhere { Tables.Reviews.id eq reviewId } }
    }

    suspend fun deleteReviewsByIds(session: DbSession, reviewIds: List<String>) {
        if (reviewIds.isEmpty()) return
        dbQuery { Tables.Reviews.deleteWhere { Tables.Reviews.id inList reviewIds } }
    }

    suspend fun deleteAllReviewsByUserId(session: DbSession, userId: String) {
        dbQuery { Tables.Reviews.deleteWhere { Tables.Reviews.authorId eq userId } }
    }

    suspend fun deleteAllReviewsByCharacterId(session: DbSession, characterId: String) {
        dbQuery { Tables.Reviews.deleteWhere { Tables.Reviews.characterId eq characterId } }
    }

    suspend fun isReviewOwnedByUser(reviewId: String, userId: String): Boolean =
        getReviewById(reviewId)?.authorId == userId
}
