package com.lvsmsmch.aichat.database_impl.mongo_db.reviews

import kotlinx.serialization.Serializable

@Serializable
data class ReviewDbo(
    val id: String,
    val isAnonymous: Boolean,
    val publishedAt: String,
    val publishedBy: String,
    val rating: Int,
    val text: String
)