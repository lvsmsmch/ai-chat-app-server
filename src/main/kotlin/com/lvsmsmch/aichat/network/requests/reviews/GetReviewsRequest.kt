package com.lvsmsmch.aichat.network.requests.reviews

data class GetReviewsRequest(
    val characterId: String,
    val filter: Int,
    val limit: Int,
    val skip: Int,
)