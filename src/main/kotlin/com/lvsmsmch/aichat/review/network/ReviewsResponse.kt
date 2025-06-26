package com.lvsmsmch.aichat.review.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReviewsResponse(
    @SerialName("reviews") val reviews: List<ReviewDto>,
    @SerialName("nextCursor") val nextCursor: String? = null,
    @SerialName("hasMore") val hasMore: Boolean = false
)