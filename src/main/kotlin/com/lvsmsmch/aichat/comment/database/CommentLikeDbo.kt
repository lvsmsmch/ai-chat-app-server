package com.lvsmsmch.aichat.comment.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class CommentLikeDbo(
    @BsonId val id: String,
    val userId: String,
    val commentId: String,
    val likedAt: String = UtcTimestamp.now().toString()
)
