package com.lvsmsmch.aichat.network.dto_objects

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable

@Serializable
data class MessageDto(
    val id: String,
    val chatId: String,
    val createdAt: UtcTimestamp,
    val isSentByUser: Boolean,
    val text: String,
    val isRead: Boolean,
    val editedAt: UtcTimestamp? = null
)
