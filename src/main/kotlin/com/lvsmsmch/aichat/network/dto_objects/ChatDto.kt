package com.lvsmsmch.aichat.network.dto_objects

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable

@Serializable
data class ChatDto(
    val id: String,
    val createdAt: UtcTimestamp,
    val userId: String,
    val characterId: String,
    val characterName: String,
    val characterDescription: String,
    val characterPrompt: String,
    val characterPicUrl: String,
    val isChatMuted: Boolean,
    val lastMessage: MessageDto?,
    val unreadMessageCount: Int
)