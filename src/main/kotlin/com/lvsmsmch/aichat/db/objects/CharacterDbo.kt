package com.lvsmsmch.aichat.db.objects

import com.lvsmsmch.aichat.db.utils.UtcTimestamp
import kotlinx.serialization.Serializable

@Serializable
data class CharacterDbo(
    val id: String,
    val name: String,
    val description: String,
    val picUrl: String,
    val prompt: String,
    val publishedAt: UtcTimestamp,
    val publishedBy: String,
    val totalMessages: Int,
    val totalReviews: Int,
    val averageRating: Float,
    val reviews: List<ReviewDbo>,
)