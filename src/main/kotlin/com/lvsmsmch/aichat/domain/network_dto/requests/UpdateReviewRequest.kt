package com.lvsmsmch.aichat.domain.network_dto.requests

import kotlinx.serialization.Serializable

@Serializable
data class UpdateReviewRequest(
    val userId: String,
    val characterId: String,
    val rating: Int,
    val text: String
)