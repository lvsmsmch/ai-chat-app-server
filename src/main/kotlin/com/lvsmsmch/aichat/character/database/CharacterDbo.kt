package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

@Serializable
data class CharacterDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val authorId: String,
    val name: String,
    val description: String,
    val prompt: String,
    val initialMessage: String,
    val picUrl: String,
    val visibility: Int,
    val category: String,
    val tags: List<String>,
    val totalChats: Int = 0,
    val totalMessages: Int = 0,
    val totalReviews: Int = 0,
    val averageRating: Float = 0f,
    val trendingScore: Float = 0f,
    val trendingScoreUpdatedAt: UtcTimestamp? = null,
    val recommendationScore: Float = 0f,
    val recommendationScoreUpdatedAt: UtcTimestamp? = null,
    val coOccurrenceScore: Map<String, Float> = emptyMap(),
    val coOccurrenceScoreUpdatedAt: UtcTimestamp? = null,
    val isDeleted: Boolean = false,
)