package com.lvsmsmch.aichat.network.requests.reviews

data class AddReviewRequest(
    val characterId: String,
    val rating: Int,
    val text: String
)