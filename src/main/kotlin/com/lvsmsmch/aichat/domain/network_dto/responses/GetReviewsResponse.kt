package com.lvsmsmch.aichat.domain.network_dto.responses

import com.lvsmsmch.aichat.domain.network_dto.objects.ReviewDto
import kotlinx.serialization.Serializable

@Serializable
data class GetReviewsResponse(
    val success: Int,
    val errorMessage: String,
    val totalItemsMatchingFilter: Int,
    val reviews: List<ReviewDto>,
)