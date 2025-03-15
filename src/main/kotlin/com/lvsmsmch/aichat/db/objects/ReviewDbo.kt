package com.lvsmsmch.aichat.db.objects

import com.lvsmsmch.aichat.db.utils.UtcTimestamp
import kotlinx.serialization.Serializable

@Serializable
data class ReviewDbo(
    val id: String,
    val isAnonymous: Boolean,
    val publishedAt: UtcTimestamp,
    val publishedBy: String,
    val rating: Int,
    val text: String
)