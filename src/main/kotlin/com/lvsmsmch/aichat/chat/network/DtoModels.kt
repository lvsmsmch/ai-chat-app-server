package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat.character.network.CharacterDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============= БАЗОВЫЕ СУЩНОСТИ =============

@Serializable
data class ChatDto(
    @SerialName("id") val id: String,
    @SerialName("chatType") val chatType: String,
    @SerialName("customName") val customName: String? = null,
    @SerialName("characters") val characters: List<CharacterDto>,
    @SerialName("createdAt") val createdAt: String
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
    @SerialName("isCompleted") val isCompleted: Boolean,
    @SerialName("isFailedCompleting") val isFailedCompleting: Boolean,
    @SerialName("nsfw") val nsfw: Boolean
)

// ============= ВСПОМОГАТЕЛЬНЫЕ МОДЕЛИ СООБЩЕНИЙ =============

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

// ============= СИНХРОНИЗАЦИЯ =============

@Serializable
data class ChatSyncRequest(
    @SerialName("chatId") val chatId: String,
    @SerialName("lastChatSyncTimestamp") val lastChatSyncTimestamp: String? = null,
    @SerialName("lastMessagesSyncTimestamp") val lastMessagesSyncTimestamp: String? = null,
    @SerialName("oldestLoadedMessageTime") val oldestLoadedMessageTime: String? = null,
    @SerialName("newestLoadedMessageTime") val newestLoadedMessageTime: String? = null
)

@Serializable
data class ChatSyncResponse(
    @SerialName("chatId") val chatId: String,
    @SerialName("chatUpdatedResponse") val chatUpdatedResponse: ChatUpdatedResponse? = null,
    @SerialName("messageListUpdatedResponse") val messageListUpdatedResponse: MessageListUpdatedResponse? = null
)

@Serializable
data class ChatUpdatedResponse(
    @SerialName("lastChatSyncTimestamp") val lastChatSyncTimestamp: String,
    @SerialName("chat") val chat: ChatDto,
    @SerialName("isChatDeleted") val isChatDeleted: Boolean
)

@Serializable
data class MessageListUpdatedResponse(
    @SerialName("lastMessagesSyncTimestamp") val lastMessagesSyncTimestamp: String,
    @SerialName("newMessages") val newMessages: List<MessageDto>,
    @SerialName("updatedMessages") val updatedMessages: List<MessageDto>,
    @SerialName("deletedMessageIds") val deletedMessageIds: List<String>
)

@Serializable
data class BatchSyncRequest(
    @SerialName("chatSyncRequests") val chatSyncRequests: List<ChatSyncRequest>
)

@Serializable
data class BatchSyncResponse(
    @SerialName("chatSyncResponses") val chatSyncResponses: List<ChatSyncResponse>
)

// ============= СОЗДАНИЕ ЧАТА =============

@Serializable
data class CreateChatRequest(
    @SerialName("chatId") val chatId: String,
    @SerialName("characterIds") val characterIds: List<String>,
    @SerialName("customName") val customName: String? = null,
    @SerialName("initialMessageId") val initialMessageId: String? = null
)

@Serializable
data class CreateChatResponse(
    @SerialName("isSuccess") val isSuccess: Boolean,
    @SerialName("chatSyncResponse") val chatSyncResponse: ChatSyncResponse
)

// ============= ОБНОВЛЕНИЕ ЧАТА =============

@Serializable
data class UpdateChatRequest(
    @SerialName("customName") val customName: String? = null,
    @SerialName("chatSyncRequest") val chatSyncRequest: ChatSyncRequest
)

@Serializable
data class UpdateChatResponse(
    @SerialName("isSuccess") val isSuccess: Boolean,
    @SerialName("chatSyncResponse") val chatSyncResponse: ChatSyncResponse
)

// ============= УДАЛЕНИЕ ЧАТОВ =============

@Serializable
data class DeleteChatsRequest(
    @SerialName("chatIds") val chatIds: List<String>
)

@Serializable
data class DeleteChatsResponse(
    @SerialName("isSuccess") val isSuccess: Boolean
)

// ============= ОТПРАВКА СООБЩЕНИЙ =============

@Serializable
data class SendMessageRequest(
    @SerialName("userMessage") val userMessage: UserMessage? = null,
    @SerialName("characterMessage") val characterMessage: CharacterMessage? = null,
    @SerialName("chatSyncRequest") val chatSyncRequest: ChatSyncRequest
)

@Serializable
data class SendMessageResponse(
    @SerialName("isSuccess") val isSuccess: Boolean,
    @SerialName("chatSyncResponse") val chatSyncResponse: ChatSyncResponse
)

// ============= ПЕРЕГЕНЕРАЦИЯ СООБЩЕНИЙ =============

@Serializable
data class ReloadMessageRequest(
    @SerialName("id") val id: String,
    @SerialName("requestId") val requestId: String,
    @SerialName("chatSyncRequest") val chatSyncRequest: ChatSyncRequest
)

@Serializable
data class ReloadMessageResponse(
    @SerialName("isSuccess") val isSuccess: Boolean,
    @SerialName("chatSyncResponse") val chatSyncResponse: ChatSyncResponse
)

// ============= СТРИМИНГ СООБЩЕНИЙ =============

@Serializable
data class StreamMessageRequest(
    @SerialName("version") val version: Int,
    @SerialName("chatSyncRequest") val chatSyncRequest: ChatSyncRequest
)

@Serializable
data class StreamMessageChunk(
    @SerialName("chunk") val chunk: String,
    @SerialName("isComplete") val isComplete: Boolean,
    @SerialName("isFailed") val isFailed: Boolean,
    @SerialName("nsfw") val nsfw: Boolean,
    @SerialName("chatSyncResponse") val chatSyncResponse: ChatSyncResponse? = null
)

// ============= ПОЛУЧЕНИЕ СООБЩЕНИЙ =============

@Serializable
data class GetMessagesRequest(
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("size") val size: Int = 50
)

@Serializable
data class GetMessagesResponse(
    @SerialName("messages") val messages: List<MessageDto>,
    @SerialName("nextCursor") val nextCursor: String?
)


// ============= РЕПОРТ СООБЩЕНИЯ =============

@Serializable
data class ReportMessageRequest(
    @SerialName("reason") val reason: String,
    @SerialName("text") val text: String = ""
)


// ============= УДАЛЕНИЕ СООБЩЕНИЙ =============

@Serializable
data class DeleteMessagesRequest(
    @SerialName("chatId") val chatId: String,
    @SerialName("messageIds") val messageIds: List<String>,
    @SerialName("chatSyncRequest") val chatSyncRequest: ChatSyncRequest
)

@Serializable
data class DeleteMessagesResponse(
    @SerialName("isSuccess") val isSuccess: Boolean,
    @SerialName("chatSyncResponse") val chatSyncResponse: ChatSyncResponse
)