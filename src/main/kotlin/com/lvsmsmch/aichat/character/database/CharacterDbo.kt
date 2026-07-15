package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat._common.AvatarColors
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

@Serializable
data class CharacterDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val createdAt: String = UtcTimestamp.now().toString(),
    val authorId: String,
    val name: String,
    val description: String,
    val prompt: String,
    val initialMessage: String,
    val picUrl: String? = null,
    val picUrlThumbnail: String? = null,
    val visibility: Int,
    val category: String,
    val tags: List<String>,
    val totalChats: Int = 0,
    val totalMessages: Int = 0,
    val totalReviews: Int = 0,
    val averageRating: Float = 0f,
    val trendingScore: Float = 0f,
    val trendingScoreUpdatedAt: String? = null,
    val recommendationScore: Float = 0f,
    val recommendationScoreUpdatedAt: String? = null,
    val recommendationsScoreMultiplier: Float? = null,
    val coOccurrenceScore: Map<String, Float> = emptyMap(),
    val coOccurrenceScoreUpdatedAt: String? = null,
    val color: String = AvatarColors.random()
)