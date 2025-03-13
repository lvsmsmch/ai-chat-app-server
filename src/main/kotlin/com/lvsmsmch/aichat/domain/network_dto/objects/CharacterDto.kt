package com.lvsmsmch.aichat.domain.network_dto.objects

import kotlinx.serialization.Serializable

@Serializable
data class CharacterDto(
    val id: String,
    val name: String,
    val description: String,
    val picUrl: String,
    val prompt: String,
    val publishedAt: String,
    val publishedBy: String,
    val totalMessages: Int,
    val totalReviews: Int,
    val averageRating: Float,
)