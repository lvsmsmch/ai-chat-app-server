package com.lvsmsmch.aichat.character.network

import com.lvsmsmch.aichat.user.network.UserDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CharacterDto(
    @SerialName("id") val id: String,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("author") val author: UserDto,
    @SerialName("visibility") val visibility: Int,
    @SerialName("name") val name: String,
    @SerialName("category") val category: String,
    @SerialName("tags") val tags: List<String>,
    @SerialName("picUrl") val picUrl: String,
    @SerialName("totalChats") val totalChats: Int,
    @SerialName("totalMessages") val totalMessages: Int,
    @SerialName("totalReviews") val totalReviews: Int,
    @SerialName("averageRating") val averageRating: Float
)