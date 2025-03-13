package com.lvsmsmch.aichat.database_impl.mongo_db.characters

import com.lvsmsmch.aichat.database_impl.mongo_db.reviews.ReviewDbo
import kotlinx.serialization.Serializable

@Serializable
data class CharacterDbo(
    val id: String,
    val name: String,
    val description: String,
    val picUrl: String,
    val prompt: String,
    val publishedAt: String,
    val publishedBy: String,
    val totalMessages: Int,
    val totalReviews: Int,
    val averageRating: Float,
    val reviews: List<ReviewDbo>,
)