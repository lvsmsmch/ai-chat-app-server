package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat.character.network.CharacterDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatDto(
    @SerialName("id") val id: String,
    @SerialName("chatType") val chatType: String,
    @SerialName("characters") val characters: List<CharacterDto>,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("lastSyncTimestamp") val lastSyncTimestamp: String
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
    @SerialName("status") val status: String,
    @SerialName("nsfw") val nsfw: Boolean
)



@Serializable
data class ChatSyncRequest(
    @SerialName("chatId") val chatId: String,
    @SerialName("lastSyncTimestamp") val lastSyncTimestamp: String? = null,
    @SerialName("oldestLoadedMessageTime") val oldestLoadedMessageTime: String? = null,
    @SerialName("newestLoadedMessageTime") val newestLoadedMessageTime: String? = null,
    @SerialName("deletedMessageIds") val deletedMessageIds: List<String>? = null,
    @SerialName("updateRequest") val updateRequest: UpdateChatRequest? = null,
    @SerialName("isDeleted") val isDeleted: Boolean? = null
)

@Serializable
data class ChatSyncResponse(
    @SerialName("chatId") val chatId: String,
    @SerialName("chat") val chat: ChatDto,
    @SerialName("newMessages") val newMessages: List<MessageDto> = emptyList(),
    @SerialName("updatedMessages") val updatedMessages: List<MessageDto> = emptyList(),
    @SerialName("deletedMessageIds") val deletedMessageIds: List<String> = emptyList(),
    @SerialName("isDeleted") val isDeleted: Boolean
)

@Serializable
data class BatchSyncRequest(
    @SerialName("chatSyncRequests") val chatSyncRequests: List<ChatSyncRequest>,
    @SerialName("fullSync") val fullSync: Boolean
)

@Serializable
data class BatchSyncResponse(
    @SerialName("chatSyncResponses") val chatSyncResponses: List<ChatSyncResponse>
)

@Serializable
data class CreateChatRequest(
    @SerialName("chatId") val chatId: String,
    @SerialName("characterIds") val characterIds: List<String>,
    @SerialName("initialMessageId") val initialMessageId: String? = null
)

@Serializable
data class CreateChatResponse(
    @SerialName("chat") val chat: ChatDto,
    @SerialName("initialMessage") val initialMessage: MessageDto? = null
)




@Serializable
data class GetMessagesRequest(
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("size") val size: Int = 50
)

@Serializable
data class GetMessagesResponse(
    @SerialName("messages") val messages: List<MessageDto>,
    @SerialName("nextCursor") val nextCursor: String?,
    @SerialName("prevCursor") val prevCursor: String?,
    @SerialName("hasMore") val hasMore: Boolean
)



@Serializable
data class SendMessageRequest(
    @SerialName("userMessage") val userMessage: UserMessage? = null,
    @SerialName("characterMessage") val characterMessage: CharacterMessage? = null,
    @SerialName("nsfwAllowed") val nsfwAllowed: Boolean,
    @SerialName("lastChatSyncTimestamp") val lastChatSyncTimestamp: String,
)

@Serializable
data class SendMessageResponse(
    @SerialName("syncRequired") val syncRequired: Boolean,
    @SerialName("userMessage") val userMessage: MessageDto? = null,
    @SerialName("characterMessage") val characterMessage: MessageDto? = null,
)



@Serializable
data class ReloadMessageRequest(
    @SerialName("nsfwAllowed") val nsfwAllowed: Boolean,
    @SerialName("lastChatSyncTimestamp") val lastChatSyncTimestamp: String,
)

@Serializable
data class ReloadMessageResponse(
    @SerialName("syncRequired") val syncRequired: Boolean
)



@Serializable
data class DeleteMessagesRequest(
    @SerialName("messageIds") val messageIds: List<String>
)

@Serializable
data class DeleteMessagesResponse(
    @SerialName("isSuccess") val isSuccess: Boolean
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
    @SerialName("customName" ) val customName: String? = null
)

@Serializable
data class UpdateChatResponse(
    @SerialName("chat") val chat: ChatDto
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

@Serializable
data class DeleteChatsRequest(
    @SerialName("chatIds") val chatIds: List<String>
)

@Serializable
data class DeleteChatsResponse(
    @SerialName("isSuccess") val isSuccess: Boolean
)

