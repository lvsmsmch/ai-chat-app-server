package com.lvsmsmch.aichat.domain.network_dto.requests

import kotlinx.serialization.Serializable

@Serializable
data class AddReviewRequest(
    val userId: String,
    val characterId: String,
    val rating: Int,
    val text: String
)