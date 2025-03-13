package com.lvsmsmch.aichat.domain.network_dto.requests

import com.lvsmsmch.aichat.domain.other.filters.ReviewFilter
import kotlinx.serialization.Serializable

@Serializable
data class GetReviewsRequest(
    val characterId: String,
    val filter: Int = ReviewFilter.NEWEST.code,
    val limit: Int = 10,
    val skip: Int = 0,
)