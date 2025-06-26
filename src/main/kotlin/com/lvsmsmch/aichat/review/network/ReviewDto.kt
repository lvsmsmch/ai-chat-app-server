package com.lvsmsmch.aichat.review.network

import com.lvsmsmch.aichat.user.network.UserDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReviewDto(
    @SerialName("id") val id: String,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("updatedAt") val updatedAt: String? = null,
    @SerialName("characterId") val characterId: String,
    @SerialName("author") val author: UserDto,
    @SerialName("rating") val rating: Int,
    @SerialName("text") val text: String,
    @SerialName("likesCount") val likesCount: Int,
    @SerialName("isLikedByCurrentUser") val isLikedByCurrentUser: Boolean = false
)