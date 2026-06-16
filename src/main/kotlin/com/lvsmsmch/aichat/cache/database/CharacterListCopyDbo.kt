package com.lvsmsmch.aichat.cache.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class CharacterListCopyDbo(
    @BsonId val id: String,
    val userId: String,
    val deviceId: String,
    val listType: String,
    val characterIds: List<String>,
    val currentPosition: Int = 0,
    val baseListVersion: String,
    val createdAt: String = UtcTimestamp.now().toString(),
    val lastAccessedAt: String = UtcTimestamp.now().toString(),
    val totalFound: Int? = null
)