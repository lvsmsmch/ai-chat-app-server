package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class SearchSuggestionDbo(
    @BsonId val term: String, // "naruto uchiha" (lowercase, trimmed)
    val displayText: String,
    val searchCount: Long = 0,
    val isAllowedToShow: Boolean = false,
    val isCharacterName: Boolean = false,
    val language: String? = null, // "en", "ja", "ru", etc
    val createdAt: String = UtcTimestamp.now().toString(),
    val lastSearchedAt: String? = null
)