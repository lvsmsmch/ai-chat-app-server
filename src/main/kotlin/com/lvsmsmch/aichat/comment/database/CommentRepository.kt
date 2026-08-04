package com.lvsmsmch.aichat.comment.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toCommentDbo
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class CommentRepository {

    suspend fun addComment(session: DbSession, comment: CommentDbo) {
        dbQuery { Tables.Comments.insert { it.from(comment) } }
    }

    suspend fun getCommentById(id: String): CommentDbo? = dbQuery {
        Tables.Comments.selectAll()
            .where { Tables.Comments.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toCommentDbo()
    }

    /**
     * Корневые комменты персонажа. Сортировки: 0 — новые сверху (курсор createdAt <),
     * 1 — старые сверху (createdAt >), 2 — топ по лайкам (курсор — смещение:
     * лайки меняются, и курсор по значению давал бы дубли и пропуски).
     */
    suspend fun getRootComments(
        characterId: String,
        sortCriteria: Int,
        cursor: String?,
        size: Int,
    ): List<CommentDbo> = dbQuery {
        val base = (Tables.Comments.characterId eq characterId) and
            (Tables.Comments.parentId eq null)
        when (sortCriteria) {
            1 -> Tables.Comments.selectAll()
                .where {
                    if (cursor == null) base else base and (Tables.Comments.createdAt greater cursor)
                }
                .orderBy(Tables.Comments.createdAt to SortOrder.ASC)
                .limit(size)
                .map { it.toCommentDbo() }

            2 -> Tables.Comments.selectAll()
                .where { base }
                .orderBy(
                    Tables.Comments.likesCount to SortOrder.DESC,
                    Tables.Comments.createdAt to SortOrder.DESC,
                )
                .limit(size)
                .offset((cursor?.toLongOrNull() ?: 0L))
                .map { it.toCommentDbo() }

            else -> Tables.Comments.selectAll()
                .where {
                    if (cursor == null) base else base and (Tables.Comments.createdAt less cursor)
                }
                .orderBy(Tables.Comments.createdAt to SortOrder.DESC)
                .limit(size)
                .map { it.toCommentDbo() }
        }
    }

    /** Ответы ветки, старые сверху (как на YouTube); курсор — createdAt последнего. */
    suspend fun getReplies(parentId: String, afterTime: UtcTimestamp?, size: Int): List<CommentDbo> =
        dbQuery {
            Tables.Comments.selectAll()
                .where {
                    val base = Tables.Comments.parentId eq parentId
                    if (afterTime == null) base
                    else base and (Tables.Comments.createdAt greater afterTime.toString())
                }
                .orderBy(Tables.Comments.createdAt to SortOrder.ASC)
                .limit(size)
                .map { it.toCommentDbo() }
        }

    suspend fun getReplyIds(session: DbSession, parentId: String): List<String> = dbQuery {
        Tables.Comments.selectAll()
            .where { Tables.Comments.parentId eq parentId }
            .map { it[Tables.Comments.id] }
    }

    suspend fun updateText(commentId: String, text: String) {
        dbQuery {
            Tables.Comments.update({ Tables.Comments.id eq commentId }) {
                it[Tables.Comments.text] = text
                it[Tables.Comments.editedAt] = UtcTimestamp.now().toString()
            }
        }
    }

    suspend fun incrementLikesCount(session: DbSession, commentId: String, increment: Int) {
        dbQuery {
            Tables.Comments.update({ Tables.Comments.id eq commentId }) {
                it[Tables.Comments.likesCount] = Tables.Comments.likesCount plus increment
            }
        }
    }

    suspend fun incrementRepliesCount(session: DbSession, commentId: String, increment: Int) {
        dbQuery {
            Tables.Comments.update({ Tables.Comments.id eq commentId }) {
                it[Tables.Comments.repliesCount] = Tables.Comments.repliesCount plus increment
            }
        }
    }

    suspend fun deleteCommentsByIds(session: DbSession, ids: List<String>) {
        if (ids.isEmpty()) return
        dbQuery { Tables.Comments.deleteWhere { Tables.Comments.id inList ids } }
    }

    suspend fun getCommentIdsByCharacterIds(
        session: DbSession,
        characterIds: List<String>,
    ): List<String> {
        if (characterIds.isEmpty()) return emptyList()
        return dbQuery {
            Tables.Comments.selectAll()
                .where { Tables.Comments.characterId inList characterIds }
                .map { it[Tables.Comments.id] }
        }
    }

    suspend fun getCommentsByUserId(session: DbSession, userId: String): List<CommentDbo> = dbQuery {
        Tables.Comments.selectAll()
            .where { Tables.Comments.authorId eq userId }
            .map { it.toCommentDbo() }
    }

    suspend fun getRepliesByParentIds(
        session: DbSession,
        parentIds: List<String>,
    ): List<CommentDbo> {
        if (parentIds.isEmpty()) return emptyList()
        return dbQuery {
            Tables.Comments.selectAll()
                .where { Tables.Comments.parentId inList parentIds }
                .map { it.toCommentDbo() }
        }
    }
}
