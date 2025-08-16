package com.lvsmsmch.aichat.notification.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import java.util.*


@Serializable
data class RecommendationsDbo(
    @BsonId val id: String = UUID.randomUUID().toString(),
    val createdAt: String = UtcTimestamp.now().toString(),
    val userId: String,
    val characterIds: List<String>,
)