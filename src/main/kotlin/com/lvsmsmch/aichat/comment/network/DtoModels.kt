package com.lvsmsmch.aichat.comment.network

import com.lvsmsmch.aichat.user.network.UserDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CommentDto(
    @SerialName("id") val id: String,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("editedAt") val editedAt: String? = null,
    @SerialName("characterId") val characterId: String,
    @SerialName("author") val author: UserDto,
    @SerialName("parentId") val parentId: String? = null,
    @SerialName("replyToUsername") val replyToUsername: String? = null,
    @SerialName("text") val text: String,
    @SerialName("likesCount") val likesCount: Int = 0,
    @SerialName("repliesCount") val repliesCount: Int = 0,
    @SerialName("isLikedByCurrentUser") val isLikedByCurrentUser: Boolean = false
)

@Serializable
data class CommentsResponse(
    @SerialName("comments") val comments: List<CommentDto>,
    @SerialName("nextCursor") val nextCursor: String? = null
)

@Serializable
data class CreateCommentRequest(
    @SerialName("characterId") val characterId: String,
    @SerialName("text") val text: String,
    /** id корневого коммента, если это ответ (ответ на ответ — тот же корень). */
    @SerialName("parentId") val parentId: String? = null,
    /** Кому отвечаем (для «@username» в ветке). */
    @SerialName("replyToUserId") val replyToUserId: String? = null
)

@Serializable
data class UpdateCommentRequest(
    @SerialName("text") val text: String
)

@Serializable
data class ReportCommentRequest(
    @SerialName("reason") val reason: String,
    @SerialName("text") val text: String = ""
)
