package com.lvsmsmch.aichat.comment.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat._common.database.ReportDbo
import com.lvsmsmch.aichat._common.database.ReportEntity
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.comment.database.CommentDbo
import com.lvsmsmch.aichat.comment.database.CommentLikeRepository
import com.lvsmsmch.aichat.comment.database.CommentRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Route.configureCommentRouting(
    sessionRepository: SessionRepository,
    commentRepository: CommentRepository,
    commentLikeRepository: CommentLikeRepository,
    characterRepository: CharacterRepository,
    reportRepository: ReportRepository,
    userRepository: UserRepository,
    idGenerator: IdGenerator,
    complexQueryHelper: ComplexQueryHelper,
    mapper: Mapper
) {

    suspend fun toDtos(comments: List<CommentDbo>, currentUserId: String): List<CommentDto> {
        val liked = commentLikeRepository.getLikedCommentIds(currentUserId, comments.map { it.id })
        return comments.mapNotNull { c ->
            val author = userRepository.getUserById(c.authorId) ?: return@mapNotNull null
            CommentDto(
                id = c.id,
                createdAt = c.createdAt,
                characterId = c.characterId,
                author = author.toUserDto(mapper),
                parentId = c.parentId,
                replyToUsername = c.replyToUserId?.let { userRepository.getUserById(it)?.username },
                text = c.text,
                likesCount = c.likesCount,
                repliesCount = c.repliesCount,
                isLikedByCurrentUser = c.id in liked
            )
        }
    }

    route("/comments") {

        post {
            val sessionDbo = sessionRepository.verifyToken(call)
            val request = call.receive<CreateCommentRequest>()

            characterRepository.getCharacter(request.characterId)
                ?: throw CharacterNotFoundException(id = request.characterId)

            validateCommentText(request.text)

            // Ответ на ответ нормализуем к корню ветки (модель YouTube: ветки плоские)
            val rootId = request.parentId?.let { parentId ->
                val parent = commentRepository.getCommentById(parentId)
                    ?: throw CommentNotFoundException(id = parentId)
                if (parent.characterId != request.characterId) {
                    throw BadRequestException("Parent comment belongs to another character")
                }
                parent.parentId ?: parent.id
            }

            val commentDbo = CommentDbo(
                id = idGenerator.generateId(EntityType.COMMENT),
                characterId = request.characterId,
                authorId = sessionDbo.userId,
                parentId = rootId,
                replyToUserId = request.replyToUserId,
                text = request.text.trim(),
            )

            complexQueryHelper.addComment(commentDbo)

            call.respondSuccess(data = toDtos(listOf(commentDbo), sessionDbo.userId).first())
        }

        get {
            val sessionDbo = sessionRepository.verifyToken(call)
            val characterId = call.request.queryParameters["characterId"]
                ?: throw BadRequestException("Missing characterId parameter")
            val cursor = call.request.queryParameters["cursor"]
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
            require(size in 1..100) { "Size must be between 1 and 100" }

            val dbos = commentRepository.getRootComments(
                characterId = characterId,
                beforeTime = cursor?.let { UtcTimestamp.parse(it) },
                size = size + 1
            )
            val hasMore = dbos.size > size
            val page = if (hasMore) dbos.dropLast(1) else dbos

            call.respondSuccess(
                data = CommentsResponse(
                    comments = toDtos(page, sessionDbo.userId),
                    nextCursor = if (hasMore) page.lastOrNull()?.createdAt else null
                )
            )
        }

        get("/{id}/replies") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val commentId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")
            val cursor = call.request.queryParameters["cursor"]
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
            require(size in 1..100) { "Size must be between 1 and 100" }

            val dbos = commentRepository.getReplies(
                parentId = commentId,
                afterTime = cursor?.let { UtcTimestamp.parse(it) },
                size = size + 1
            )
            val hasMore = dbos.size > size
            val page = if (hasMore) dbos.dropLast(1) else dbos

            call.respondSuccess(
                data = CommentsResponse(
                    comments = toDtos(page, sessionDbo.userId),
                    nextCursor = if (hasMore) page.lastOrNull()?.createdAt else null
                )
            )
        }

        patch("/{id}") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val commentId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")
            val request = call.receive<UpdateCommentRequest>()

            val commentDbo = commentRepository.getCommentById(commentId)
                ?: throw CommentNotFoundException(id = commentId)

            if (commentDbo.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to edit this comment")
            }
            validateCommentText(request.text)

            commentRepository.updateText(commentId, request.text.trim())

            call.respondSuccess(
                data = toDtos(listOf(commentDbo.copy(text = request.text.trim())), sessionDbo.userId).first()
            )
        }

        delete("/{id}") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val commentId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")

            val commentDbo = commentRepository.getCommentById(commentId)
                ?: throw CommentNotFoundException(id = commentId)

            if (commentDbo.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to delete this comment")
            }

            complexQueryHelper.deleteComment(commentDbo)

            call.respondSuccess()
        }

        post("/{id}/like") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val commentId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")

            commentRepository.getCommentById(commentId)
                ?: throw CommentNotFoundException(id = commentId)

            // Идемпотентно: повторный лайк не ошибка (оптимистичный UI может дублировать)
            if (!commentLikeRepository.isCommentLikedByUser(sessionDbo.userId, commentId)) {
                complexQueryHelper.likeComment(commentId, sessionDbo.userId)
            }
            call.respondSuccess()
        }

        post("/{id}/unlike") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val commentId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")

            commentRepository.getCommentById(commentId)
                ?: throw CommentNotFoundException(id = commentId)

            if (commentLikeRepository.isCommentLikedByUser(sessionDbo.userId, commentId)) {
                complexQueryHelper.unlikeComment(commentId, sessionDbo.userId)
            }
            call.respondSuccess()
        }

        post("/{id}/report") {
            val currentUserId = sessionRepository.verifyToken(call).userId
            val commentId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")
            val request = call.receive<ReportCommentRequest>()

            reportRepository.addReport(
                ReportDbo(
                    reportedBy = currentUserId,
                    entityType = ReportEntity.Comment.code,
                    entityId = commentId,
                    reason = request.reason,
                    text = request.text
                )
            )

            call.respondSuccess()
        }
    }
}
