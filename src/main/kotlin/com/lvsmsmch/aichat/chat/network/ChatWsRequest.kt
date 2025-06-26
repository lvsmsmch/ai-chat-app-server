package com.lvsmsmch.aichat.chat.network
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AddChatDto(
    val chatType: String,
    val characterIds: List<String>,
    val isChatMuted: Boolean
)

@Serializable
data class UpdateChatDto(
    val isChatMuted: Boolean
)

@Serializable
data class AddMessageDto(
    val messageId: String,
    val senderId: String,
    val text: String,
    val isRead: Boolean,
    val completedStatus: String
)

@Serializable
data class UpdateMessageDto(
    val messageId: String,
    val isRead: Boolean? = null,
    val completedStatus: String? = null,
)

@Serializable
sealed class ChatWsRequest {

    @Serializable
    @SerialName("sync_chat_list_request")
    data class SyncChatListRequest(
        val lastSyncTimestamp: UtcTimestamp? = null,
    ) : ChatWsRequest()

    @Serializable
    @SerialName("add_chat_request")
    data class AddChatRequest(
        val chatId: String,
        val addChatDto: AddChatDto,
        val addMessages: List<AddMessageDto>? = null,
    ) : ChatWsRequest()

    @Serializable
    @SerialName("update_chat_request")
    data class UpdateChatRequest(
        val lastSyncTimestamp: UtcTimestamp? = null,
        val chatId: String,
        val updateChatDto: UpdateChatDto? = null,
        val addMessages: List<AddMessageDto>? = null,
        val updateMessages: List<UpdateMessageDto>? = null,
        val deleteMessageIds: List<String>? = null,
    ) : ChatWsRequest()

    @Serializable
    @SerialName("delete_chat_request")
    data class DeleteChatRequest(
        val lastSyncTimestamp: UtcTimestamp? = null,
        val chatId: String
    ) : ChatWsRequest()
}