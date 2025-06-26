package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AddedChatDto(
    val createdAt: String,
    val chatType: String,
    val characterIds: List<String>,
    val isChatMuted: Boolean
)

@Serializable
data class UpdatedChatDto(
    val chatType: String,
    val characterIds: List<String>,
    val isChatMuted: Boolean
)

@Serializable
data class AddedMessageDto(
    val messageId: String,
    val createdAt: String,
    val senderId: String,
    val text: String,
    val isRead: Boolean,
    val completedStatus: String,
)

@Serializable
data class UpdatedMessageDto(
    val messageId: String,
    val text: String,
    val isRead: Boolean,
    val completedStatus: String,
)

@Serializable
sealed class ChatWsEvent {

//    @Serializable
//    @SerialName("sync_chat_list_finished")
//    data class SyncChatListFinished(
//        val lastSyncTimestamp: UtcTimestamp,
//    ) : ChatWsEvent()

    @Serializable
    @SerialName("chat_added")
    data class ChatAdded(
        val serverTimestamp: UtcTimestamp,
        val chatId: String,
        val addedChatDto: AddedChatDto,
        val addedMessages: List<AddedMessageDto>? = null
    ) : ChatWsEvent()

    @Serializable
    @SerialName("chat_add_failed")
    data class ChatAddFailed(
        val serverTimestamp: UtcTimestamp,
        val chatId: String
    ) : ChatWsEvent()

    @Serializable
    @SerialName("chat_updated")
    data class ChatUpdated(
        val serverTimestamp: UtcTimestamp,
        val chatId: String,
        val updatedChatDto: UpdatedChatDto? = null,
        val addedMessages: List<AddedMessageDto>? = null,
        val updatedMessages: List<UpdatedMessageDto>? = null,
        val deletedMessagesIds: List<String>? = null
    ) : ChatWsEvent()

    @Serializable
    @SerialName("chat_deleted")
    data class ChatDeleted(
        val serverTimestamp: UtcTimestamp,
        val chatId: String
    ) : ChatWsEvent()

    @Serializable
    @SerialName("error")
    data class Error(val message: String) : ChatWsEvent()
}