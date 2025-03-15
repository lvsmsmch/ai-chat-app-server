package com.lvsmsmch.aichat.network.requests.reviews

data class UpdateReviewRequest(
    val userId: String,
    val characterId: String,
    val rating: Int,
    val text: String
)