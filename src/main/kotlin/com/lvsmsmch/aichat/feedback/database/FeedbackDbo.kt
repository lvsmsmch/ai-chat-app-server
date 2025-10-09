package com.lvsmsmch.aichat.feedback.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

@Serializable
data class FeedbackDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val createdAt: String = UtcTimestamp.now().toString(),
    val userId: String,
    val text: String
)