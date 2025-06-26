package com.lvsmsmch.aichat.cache.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class CharacterListCopyDbo(
    @BsonId val id: String, // "${userId}_${deviceId}_${listType.code}"
    val userId: String,
    val deviceId: String,
    val listType: String, // "personalized" | "category_anime" | "search_abc123def456"
    val characterIds: List<String>,
    val currentPosition: Int = 0,
    val baseListVersion: String,
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val lastAccessedAt: UtcTimestamp = UtcTimestamp.now(),
    val totalFound: Int? = null
)