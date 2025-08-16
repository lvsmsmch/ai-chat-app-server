package com.lvsmsmch.aichat.chat.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import java.util.*

@Serializable
data class ChatDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val clientId: String = UUID.randomUUID().toString(),
    val lastModifiedAt: String = UtcTimestamp.now().toString(),
    val createdAt: String = UtcTimestamp.now().toString(),
    val type: ChatType = ChatType.DIRECT,
    val userId: String,
    val characterIds: List<String> = listOf(),
    val isMuted: Boolean = false,
    val customName: String? = null,
    val isFirstChatWithThisCharacter: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: String = UtcTimestamp.now().toString(),
)