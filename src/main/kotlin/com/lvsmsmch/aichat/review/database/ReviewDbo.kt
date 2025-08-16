package com.lvsmsmch.aichat.review.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class ReviewDbo(
    @BsonId val id: String,
    val createdAt: String = UtcTimestamp.now().toString(),
    val characterId: String,
    val isAnonymous: Boolean,
    val authorId: String,
    val rating: Int,
    val text: String? = null,
    val likesCount: Int = 0,
    val editedAt: String? = null
)