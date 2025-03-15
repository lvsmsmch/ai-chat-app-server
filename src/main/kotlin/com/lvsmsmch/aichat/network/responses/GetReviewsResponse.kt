package com.lvsmsmch.aichat.network.responses

import com.lvsmsmch.aichat.network.objects.ReviewDto
import kotlinx.serialization.Serializable

@Serializable
data class GetReviewsResponse(
    val success: Int,
    val errorMessage: String,
    val totalItemsMatchingFilter: Int,
    val reviews: List<ReviewDto>,
)