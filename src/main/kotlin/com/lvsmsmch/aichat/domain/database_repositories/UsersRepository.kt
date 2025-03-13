package com.lvsmsmch.aichat.domain.database_repositories

import com.lvsmsmch.aichat.domain.network_dto.objects.ReviewDto
import com.lvsmsmch.aichat.domain.network_dto.requests.AddReviewRequest
import com.lvsmsmch.aichat.domain.network_dto.requests.GetMyReviewRequest
import com.lvsmsmch.aichat.domain.network_dto.requests.GetReviewsRequest
import com.lvsmsmch.aichat.domain.network_dto.requests.UpdateReviewRequest

interface UsersRepository {
    suspend fun insertUser(getRevReviewsRequest: GetReviewsRequest): List<ReviewDto>
    suspend fun getMyReview(getMyReviewRequest: GetMyReviewRequest): ReviewDto?
    suspend fun updateReview(updateReviewRequest: UpdateReviewRequest): Boolean
    suspend fun addReview(addReviewRequest: AddReviewRequest): Boolean
}