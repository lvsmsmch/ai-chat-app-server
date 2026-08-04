package com.lvsmsmch.aichat.comment.network

import com.lvsmsmch.aichat._common.database.ReportDbo
import com.lvsmsmch.aichat._common.database.ReportEntity
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.comment.CommentService
import com.lvsmsmch.aichat.utils.BadRequestException
import com.lvsmsmch.aichat.utils.CharacterNotFoundException
import com.lvsmsmch.aichat.utils.respondSuccess
import com.lvsmsmch.aichat.utils.validateCommentText
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

/**
 * HTTP-слой комментариев: разбор параметров, вызов [CommentService], код
 * ответа. Бизнес-логики здесь нет намеренно — она в сервисе.
 */
fun Route.configureCommentRouting(
    sessionRepository: SessionRepository,
    characterRepository: CharacterRepository,
    reportRepository: ReportRepository,
    commentService: CommentService,
) {
    route("/comments") {

        post {
            val sessionDbo = sessionRepository.verifyToken(call)
            val request = call.receive<CreateCommentRequest>()

            characterRepository.getCharacter(request.characterId)
                ?: throw CharacterNotFoundException(id = request.characterId)
            validateCommentText(request.text)

            call.respondSuccess(
                data = commentService.add(
                    characterId = request.characterId,
                    authorId = sessionDbo.userId,
                    text = request.text,
                    parentId = request.parentId,
                    replyToUserId = request.replyToUserId,
                )
            )
        }

        get {
            val sessionDbo = sessionRepository.verifyToken(call)
            val characterId = call.request.queryParameters["characterId"]
                ?: throw BadRequestException("Missing characterId parameter")
            val sortCriteria = call.request.queryParameters["sortCriteria"]?.toIntOrNull() ?: 0
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
            require(size in 1..100) { "Size must be between 1 and 100" }
            require(sortCriteria in 0..2) { "Unknown sortCriteria" }

            val page = commentService.rootComments(
                characterId = characterId,
                currentUserId = sessionDbo.userId,
                sortCriteria = sortCriteria,
                cursor = call.request.queryParameters["cursor"],
                size = size,
            )
            call.respondSuccess(
                data = CommentsResponse(comments = page.comments, nextCursor = page.nextCursor)
            )
        }

        get("/{id}/replies") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val commentId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
            require(size in 1..100) { "Size must be between 1 and 100" }

            val page = commentService.replies(
                parentId = commentId,
                currentUserId = sessionDbo.userId,
                cursor = call.request.queryParameters["cursor"],
                size = size,
            )
            call.respondSuccess(
                data = CommentsResponse(comments = page.comments, nextCursor = page.nextCursor)
            )
        }

        patch("/{id}") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val commentId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")
            val request = call.receive<UpdateCommentRequest>()

            call.respondSuccess(
                data = commentService.edit(commentId, sessionDbo.userId, request.text)
            )
        }

        delete("/{id}") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val commentId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")

            commentService.delete(commentId, sessionDbo.userId)
            call.respondSuccess()
        }

        post("/{id}/like") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val commentId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")

            commentService.like(commentId, sessionDbo.userId)
            call.respondSuccess()
        }

        post("/{id}/unlike") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val commentId = call.parameters["id"]
                ?: throw BadRequestException("Missing id parameter")

            commentService.unlike(commentId, sessionDbo.userId)
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
                    text = request.text,
                )
            )
            call.respondSuccess()
        }
    }
}
