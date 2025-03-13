package com.lvsmsmch.aichat.domain.network_dto.requests

import kotlinx.serialization.Serializable

@Serializable
data class GetMyReviewRequest(
    val userId: String,
    val characterId: String,
)