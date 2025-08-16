package com.lvsmsmch.aichat.review.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class ReviewLikeDbo(
    @BsonId val id: String, // "${userId}_${reviewId}"
    val userId: String,
    val reviewId: String,
    val likedAt: String = UtcTimestamp.now().toString()
)