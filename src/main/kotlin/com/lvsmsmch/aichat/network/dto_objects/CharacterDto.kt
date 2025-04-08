package com.lvsmsmch.aichat.network.dto_objects

import kotlinx.serialization.Serializable

@Serializable
data class CharacterDto(
    val id: String,
    val createdAt: String,
    val authorUsername: String,
    val name: String,
    val description: String,
    val picUrl: String,
    val totalChats: Int,
    val totalMessages: Int,
    val totalReviews: Int,
    val averageRating: Float,
)