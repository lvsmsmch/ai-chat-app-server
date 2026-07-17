package com.lvsmsmch.aichat.chat.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import java.util.*

@Serializable
data class MessageDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val clientId: String = UUID.randomUUID().toString(),
    val lastModifiedAt: String = UtcTimestamp.now().toString(),
    val createdAt: String = UtcTimestamp.now().toString(),
    val chatId: String,
    val chatClientId: String,
    val senderId: String,
    val isSentByUser: Boolean = false,
    val text: String,
    val imageUrl: String? = null,
    val isRead: Boolean = false,
    val status: String,
    // Причина FAILED: "censored" (фильтр контента) или "error" (сбой генерации/сервера)
    val failReason: String? = null,
    val nsfw: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: String = UtcTimestamp.now().toString(),
)