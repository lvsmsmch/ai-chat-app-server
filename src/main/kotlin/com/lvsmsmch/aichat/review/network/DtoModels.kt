package com.lvsmsmch.aichat.review.network

import com.lvsmsmch.aichat.user.network.UserDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============= RESPONSE MODELS =============

@Serializable
data class ReviewDto(
    @SerialName("id") val id: String,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("updatedAt") val updatedAt: String? = null,
    @SerialName("characterId") val characterId: String,
    @SerialName("author") val author: UserDto,
    @SerialName("rating") val rating: Int,
    @SerialName("text") val text: String? = null,
    @SerialName("likesCount") val likesCount: Int,
    @SerialName("isLikedByCurrentUser") val isLikedByCurrentUser: Boolean = false
)

@Serializable
data class ReviewsResponse(
    @SerialName("reviews") val reviews: List<ReviewDto>,
    @SerialName("nextCursor") val nextCursor: String? = null,
    @SerialName("hasMore") val hasMore: Boolean = false
)

@Serializable
data class ReviewLikesResponse(
    @SerialName("users") val users: List<UserDto>
)

// ============= REQUEST MODELS =============

@Serializable
data class CreateReviewRequest(
    @SerialName("characterId") val characterId: String,
    @SerialName("rating") val rating: Int,
    @SerialName("text") val text: String? = null
)

@Serializable
data class GetReviewsRequest(
    @SerialName("characterId") val characterId: String,
    @SerialName("sortCriteria") val sortCriteria: Int = 0,
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("size") val size: Int = 10
)

@Serializable
data class UpdateReviewRequest(
    @SerialName("rating") val rating: Int? = null,
    @SerialName("text") val text: String? = null
)

@Serializable
data class ReportReviewRequest(
    @SerialName("reason") val reason: String,
    @SerialName("text") val text: String = ""
)

@Serializable
data class GetReviewLikesRequest(
    @SerialName("limit") val limit: Int = 10
)