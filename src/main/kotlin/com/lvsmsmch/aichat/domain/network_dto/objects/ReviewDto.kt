package com.lvsmsmch.aichat.domain.network_dto.objects

import kotlinx.serialization.Serializable

@Serializable
data class ReviewDto(
    val id: String,
    val publishedAt: String,
    val publishedBy: String,
    val rating: Int,
    val text: String
)