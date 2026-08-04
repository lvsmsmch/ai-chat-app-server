package com.lvsmsmch.aichat.comment.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.utils.DbSession
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

class CommentLikeRepository {

    /** id = "<userId>_<commentId>": естественная уникальность лайка. */
    private fun likeId(userId: String, commentId: String) = "${userId}_${commentId}"

    suspend fun likeComment(session: DbSession, userId: String, commentId: String) {
        dbQuery {
            val id = likeId(userId, commentId)
            val exists = Tables.CommentLikes.selectAll()
                .where { Tables.CommentLikes.id eq id }
                .limit(1)
                .any()
            if (!exists) {
                Tables.CommentLikes.insert {
                    it.from(CommentLikeDbo(id = id, userId = userId, commentId = commentId))
                }
            }
        }
    }

    suspend fun unlikeComment(session: DbSession, userId: String, commentId: String) {
        dbQuery {
            Tables.CommentLikes.deleteWhere { Tables.CommentLikes.id eq likeId(userId, commentId) }
        }
    }

    suspend fun isCommentLikedByUser(userId: String, commentId: String): Boolean = dbQuery {
        Tables.CommentLikes.selectAll()
            .where { Tables.CommentLikes.id eq likeId(userId, commentId) }
            .limit(1)
            .any()
    }

    /** Батч-проверка лайков текущего юзера для страницы комментов. */
    suspend fun getLikedCommentIds(userId: String, commentIds: List<String>): Set<String> {
        if (commentIds.isEmpty()) return emptySet()
        return dbQuery {
            Tables.CommentLikes.selectAll()
                .where {
                    (Tables.CommentLikes.userId eq userId) and
                        (Tables.CommentLikes.commentId inList commentIds)
                }
                .map { it[Tables.CommentLikes.commentId] }
                .toSet()
        }
    }

    suspend fun removeAllLikesForComments(session: DbSession, commentIds: List<String>) {
        if (commentIds.isEmpty()) return
        dbQuery {
            Tables.CommentLikes.deleteWhere { Tables.CommentLikes.commentId inList commentIds }
        }
    }
}
