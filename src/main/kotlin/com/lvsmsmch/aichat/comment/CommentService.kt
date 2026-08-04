package com.lvsmsmch.aichat.comment

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.DeletedIdsStatsRepository
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.character.database.ActivityType
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.comment.database.CommentDbo
import com.lvsmsmch.aichat.comment.database.CommentLikeRepository
import com.lvsmsmch.aichat.comment.database.CommentRepository
import com.lvsmsmch.aichat.comment.network.CommentDto
import com.lvsmsmch.aichat.notification.NotificationService
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.BadRequestException
import com.lvsmsmch.aichat.utils.CommentNotFoundException
import com.lvsmsmch.aichat.utils.ForbiddenException
import com.lvsmsmch.aichat.utils.Mapper
import com.lvsmsmch.aichat.utils.TransactionHelper
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.collapseExcessLineBreaks
import com.lvsmsmch.aichat.utils.toUserDto
import com.lvsmsmch.aichat.utils.validateCommentText

/** Страница комментов: сами комменты и курсор следующей. */
data class CommentPage(val comments: List<CommentDto>, val nextCursor: String?)

/**
 * Комментарии: всё, что происходит вокруг них, кроме HTTP.
 *
 * Раньше эта логика была размазана: часть лежала прямо в роутинге (нормализация
 * веток, проверки прав, пагинация), часть — в общем на весь проект
 * `ComplexQueryHelper`. Теперь один домен — один класс, и роутинг занят только
 * разбором запроса и кодом ответа.
 */
class CommentService(
    private val commentRepository: CommentRepository,
    private val commentLikeRepository: CommentLikeRepository,
    private val characterRepository: CharacterRepository,
    private val characterActivityLogRepository: CharacterActivityLogRepository,
    private val deletedIdsStatsRepository: DeletedIdsStatsRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val transactionHelper: TransactionHelper,
    private val idGenerator: IdGenerator,
    private val mapper: Mapper,
) {

    /**
     * Новый коммент или ответ. Ответ на ответ нормализуется к корню ветки:
     * модель как на YouTube — ветки плоские, вложенности глубже одной нет.
     */
    suspend fun add(
        characterId: String,
        authorId: String,
        text: String,
        parentId: String?,
        replyToUserId: String?,
    ): CommentDto {
        val rootId = parentId?.let { id ->
            val parent = commentRepository.getCommentById(id)
                ?: throw CommentNotFoundException(id = id)
            if (parent.characterId != characterId) {
                throw BadRequestException("Parent comment belongs to another character")
            }
            parent.parentId ?: parent.id
        }

        val comment = CommentDbo(
            id = idGenerator.generateId(EntityType.COMMENT),
            characterId = characterId,
            authorId = authorId,
            parentId = rootId,
            replyToUserId = replyToUserId,
            text = collapseExcessLineBreaks(text.trim()),
        )

        transactionHelper.withTransaction { session ->
            commentRepository.addComment(session, comment)
            characterRepository.incrementCommentsCount(session, comment.characterId, 1)
            comment.parentId?.let { commentRepository.incrementRepliesCount(session, it, 1) }
            characterActivityLogRepository.logActivity(
                session = session,
                activityType = ActivityType.COMMENT_ADDED,
                characterId = comment.characterId,
                userId = comment.authorId,
            )
        }
        // Уведомление — после коммита: автору персонажа о новом комменте
        notificationService.onCharacterComment(comment)

        return toDtos(listOf(comment), authorId).first()
    }

    /** Корневые комменты персонажа. [size] — сколько отдать клиенту. */
    suspend fun rootComments(
        characterId: String,
        currentUserId: String,
        sortCriteria: Int,
        cursor: String?,
        size: Int,
    ): CommentPage {
        val dbos = commentRepository.getRootComments(
            characterId = characterId,
            sortCriteria = sortCriteria,
            cursor = cursor,
            // На один больше запрошенного — так узнаём, есть ли следующая страница
            size = size + 1,
        )
        val hasMore = dbos.size > size
        val page = if (hasMore) dbos.dropLast(1) else dbos
        val nextCursor = when {
            !hasMore -> null
            // Топ по лайкам пагинируется смещением: лайки меняются, и курсор
            // по значению давал бы дубли и пропуски
            sortCriteria == SORT_TOP -> ((cursor?.toIntOrNull() ?: 0) + page.size).toString()
            else -> page.lastOrNull()?.createdAt
        }
        return CommentPage(toDtos(page, currentUserId), nextCursor)
    }

    /** Ответы ветки, старые сверху. */
    suspend fun replies(
        parentId: String,
        currentUserId: String,
        cursor: String?,
        size: Int,
    ): CommentPage {
        val dbos = commentRepository.getReplies(
            parentId = parentId,
            afterTime = cursor?.let { UtcTimestamp.parse(it) },
            size = size + 1,
        )
        val hasMore = dbos.size > size
        val page = if (hasMore) dbos.dropLast(1) else dbos
        return CommentPage(
            comments = toDtos(page, currentUserId),
            nextCursor = if (hasMore) page.lastOrNull()?.createdAt else null,
        )
    }

    suspend fun edit(commentId: String, userId: String, text: String): CommentDto {
        val comment = ownedComment(commentId, userId, "edit")
        validateCommentText(text)
        val editedText = collapseExcessLineBreaks(text.trim())
        commentRepository.updateText(commentId, editedText)
        return toDtos(
            listOf(comment.copy(text = editedText, editedAt = UtcTimestamp.now().toString())),
            userId,
        ).first()
    }

    /** Удаление: корневой коммент уносит с собой всю ветку ответов. */
    suspend fun delete(commentId: String, userId: String) {
        val comment = ownedComment(commentId, userId, "delete")
        transactionHelper.withTransaction { session ->
            val ids = mutableListOf(comment.id)
            if (comment.parentId == null) {
                ids += commentRepository.getReplyIds(session, comment.id)
            } else {
                commentRepository.incrementRepliesCount(session, comment.parentId, -1)
            }
            commentRepository.deleteCommentsByIds(session, ids)
            deletedIdsStatsRepository.entitiesWereDeleted(session, EntityType.COMMENT, ids)
            commentLikeRepository.removeAllLikesForComments(session, ids)
            characterRepository.incrementCommentsCount(session, comment.characterId, -ids.size)
        }
    }

    /** Идемпотентно: повторный лайк не ошибка — оптимистичный UI может дублировать. */
    suspend fun like(commentId: String, userId: String) {
        val comment = commentRepository.getCommentById(commentId)
            ?: throw CommentNotFoundException(id = commentId)
        if (commentLikeRepository.isCommentLikedByUser(userId, commentId)) return

        transactionHelper.withTransaction { session ->
            commentLikeRepository.likeComment(session, userId, commentId)
            commentRepository.incrementLikesCount(session, commentId, 1)
        }
        // Автору коммента — стакающееся «+1 лайк»
        notificationService.onCommentLiked(comment, userId)
    }

    suspend fun unlike(commentId: String, userId: String) {
        commentRepository.getCommentById(commentId)
            ?: throw CommentNotFoundException(id = commentId)
        if (!commentLikeRepository.isCommentLikedByUser(userId, commentId)) return

        transactionHelper.withTransaction { session ->
            commentLikeRepository.unlikeComment(session, userId, commentId)
            commentRepository.incrementLikesCount(session, commentId, -1)
        }
    }

    private suspend fun ownedComment(
        commentId: String,
        userId: String,
        action: String,
    ): CommentDbo {
        val comment = commentRepository.getCommentById(commentId)
            ?: throw CommentNotFoundException(id = commentId)
        if (comment.authorId != userId) {
            throw ForbiddenException(errorMessage = "You are not allowed to $action this comment")
        }
        return comment
    }

    /**
     * Комменты в DTO. Авторы и адресаты ответов — ОДНИМ запросом на страницу:
     * иначе каждый коммент тянул бы своего автора и адресата отдельно.
     */
    private suspend fun toDtos(comments: List<CommentDbo>, currentUserId: String): List<CommentDto> {
        if (comments.isEmpty()) return emptyList()
        val liked = commentLikeRepository.getLikedCommentIds(currentUserId, comments.map { it.id })
        val userIds = comments.flatMap { listOfNotNull(it.authorId, it.replyToUserId) }.toSet()
        val users = userRepository.getUsersByIds(userIds).associateBy { it.id }
        return comments.mapNotNull { c ->
            // Автор удалён вместе с аккаунтом — коммент пропускаем
            val author = users[c.authorId] ?: return@mapNotNull null
            CommentDto(
                id = c.id,
                createdAt = c.createdAt,
                editedAt = c.editedAt,
                characterId = c.characterId,
                author = author.toUserDto(mapper),
                parentId = c.parentId,
                replyToUsername = c.replyToUserId?.let { users[it]?.username },
                text = c.text,
                likesCount = c.likesCount,
                repliesCount = c.repliesCount,
                isLikedByCurrentUser = c.id in liked,
            )
        }
    }

    private companion object {
        const val SORT_TOP = 2
    }
}
