package com.lvsmsmch.aichat.network.dto_objects

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

@Serializable
data class ChatDto(
    @BsonId val id: String = ObjectId().toHexString(),
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val userId: String,
    val characterId: String,
    val characterName: String,
    val characterDescription: String,
    val characterPrompt: String,
    val characterPicUrl: String,
    val isChatMuted: Boolean,
)