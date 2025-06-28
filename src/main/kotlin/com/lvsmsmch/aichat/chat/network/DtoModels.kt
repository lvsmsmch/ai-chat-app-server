// ============= UPDATED CHAT MODELS =============

package com.lvsmsmch.aichat.chat.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


// ============= RESPONSE MODELS =============

@Serializable
data class ChatDto(
    @SerialName("id") val id: String,
    @SerialName("chatType") val chatType: String,
    @SerialName("characters") val characters: List<CharacterDto>,
    @SerialName("isMuted") val isMuted: Boolean,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("updatedAt") val updatedAt: String,
)

@Serializable
data class MessageDto(
    @SerialName("id") val id: String,
    @SerialName("chatId") val chatId: String,
    @SerialName("text") val text: String,
    @SerialName("senderId") val senderId: String,
    @SerialName("isFromUser") val isFromUser: Boolean,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("isRead") val isRead: Boolean,
    @SerialName("status") val status: String
)

// ============= BATCH SYNC =============

@Serializable
data class ChatSyncRequest(
    @SerialName("chatId") val chatId: String,
    @SerialName("lastSyncTimestamp") val lastSyncTimestamp: String? = null,
    @SerialName("oldestLoadedMessageTime") val oldestLoadedMessageTime: String? = null,
    @SerialName("newestLoadedMessageTime") val newestLoadedMessageTime: String? = null,
    @SerialName("deletedMessageIds") val deletedMessageIds: List<String>? = null,
    @SerialName("isDeleted") val isDeleted: Boolean? = null
)

@Serializable
data class ChatSyncResponse(
    @SerialName("chatId") val chatId: String,
    @SerialName("chat") val chat: ChatDto?,
    @SerialName("newMessages") val newMessages: List<MessageDto>,
    @SerialName("updatedMessages") val updatedMessages: List<MessageDto>,
    @SerialName("deletedMessageIds") val deletedMessageIds: List<String>,
    @SerialName("lastSyncTimestamp") val lastSyncTimestamp: String
)

@Serializable
data class BatchSyncRequest(
    @SerialName("chatSyncRequests") val chatSyncRequests: List<ChatSyncRequest>
)

@Serializable
data class BatchSyncResponse(
    @SerialName("chatSyncResponses") val chatSyncResponses: List<ChatSyncResponse>,
    @SerialName("deletedChatIds") val deletedChatIds: List<String>
)

// ============= CHAT CREATION =============

@Serializable
data class CreateDirectChatRequest(
    @SerialName("chatId") val chatId: String,
    @SerialName("characterId") val characterId: String,
    @SerialName("initialMessageId") val initialMessageId: String
)

@Serializable
data class CreateGroupChatRequest(
    @SerialName("chatId") val chatId: String,
    @SerialName("characterIds") val characterIds: List<String>
)

@Serializable
data class CreateChatResponse(
    @SerialName("chat") val chat: ChatDto,
    @SerialName("initialMessage") val initialMessage: MessageDto? = null
)

// ============= MESSAGES =============

@Serializable
data class GetMessagesRequest(
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("limit") val limit: Int = 50
)

@Serializable
data class GetMessagesResponse(
    @SerialName("messages") val messages: List<MessageDto>,
    @SerialName("nextCursor") val nextCursor: String?,
    @SerialName("prevCursor") val prevCursor: String?,
    @SerialName("hasMore") val hasMore: Boolean
)

@Serializable
data class UserMessage(
    @SerialName("id") val id: String,
    @SerialName("text") val text: String
)

@Serializable
data class CharacterMessage(
    @SerialName("id") val id: String,
    @SerialName("characterId") val characterId: String
)

@Serializable
data class SendMessageRequest(
    @SerialName("userMessage") val userMessage: UserMessage? = null,
    @SerialName("characterMessage") val characterMessage: CharacterMessage? = null,
    @SerialName("lastChatSyncTimestamp") val lastChatSyncTimestamp: String? = null,
)

@Serializable
data class SendMessageResponse(
    @SerialName("syncRequired") val syncRequired: Boolean,
    @SerialName("userMessage") val userMessage: MessageDto? = null,
    @SerialName("characterMessage") val characterMessage: MessageDto? = null,
)

@Serializable
data class MarkAsReadRequest(
    @SerialName("messageIds") val messageIds: List<String>
)

@Serializable
data class MarkAsReadResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("updatedCount") val updatedCount: Int
)

@Serializable
data class UpdateChatRequest(
    @SerialName("isMuted") val isMuted: Boolean? = null
)

@Serializable
data class UpdateChatResponse(
    @SerialName("chat") val chat: ChatDto
)

// ============= SSE STREAMING =============

@Serializable
data class MessageStreamChunk(
    @SerialName("chunk") val chunk: String,
    @SerialName("isComplete") val isComplete: Boolean,
    @SerialName("status") val status: String,
    @SerialName("error") val error: String? = null
)