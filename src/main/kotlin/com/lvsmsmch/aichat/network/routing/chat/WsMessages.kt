package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.ChangeEvent
import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.toMessageDto
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.db.repositories.content.MessageRepository
import com.lvsmsmch.aichat.network.dto_objects.MessageDto
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import com.lvsmsmch.aichat.utils.AiMessageGeneratorUtil
import com.lvsmsmch.aichat.utils.InactivityBuffer
import com.lvsmsmch.aichat.utils.defaultJson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.encodeToString
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
sealed class MessagesWsRequest {

    /**
     *  {
     *    "type": "send_message",
     *    "text": "Hello, how are you today?"
     *  }
     */

    @Serializable
    @SerialName("send_message")
    data class SendMessage(
        val text: String
    ) : MessagesWsRequest()

    @Serializable
    @SerialName("reload_message")
    data class ReloadMessage(
        val messageId: String
    ) : MessagesWsRequest()

    @Serializable
    @SerialName("edit_message")
    data class EditMessage(
        val messageId: String,
        val text: String
    ) : MessagesWsRequest()

    @Serializable
    @SerialName("mark_as_read")
    data class MarkAsRead(
        val messageId: String
    ) : MessagesWsRequest()

    @Serializable
    @SerialName("mark_as_read_all")
    data object MarkAsReadAll : MessagesWsRequest()

    @Serializable
    @SerialName("delete_message")
    data class DeleteMessage(
        val messageId: String
    ) : MessagesWsRequest()

    @Serializable
    @SerialName("delete_message_range")
    data class DeleteMessageRange(
        val messageIds: List<String>
    ) : MessagesWsRequest()

    @Serializable
    @SerialName("delete_all_messages")
    data object DeleteAllMessages : MessagesWsRequest()
}


@Serializable
sealed class MessagesWsEvent {

    /**
     *  {
     *      "type": "error",
     *      "message": "example123"
     *  }
     */

    @Serializable
    @SerialName("new_message")
    data class NewMessage(
        val message: MessageDto
    ) : MessagesWsEvent()

    @Serializable
    @SerialName("message_edited")
    data class MessageEdited(
        val message: MessageDto
    ) : MessagesWsEvent()

    @Serializable
    @SerialName("character_typing")
    data class CharacterTyping(
        val isTyping: Boolean
    ) : MessagesWsEvent()

    @Serializable
    @SerialName("messages_range_deleted")
    data class MessageRangeDeleted(
        val messageIds: List<String>
    ) : MessagesWsEvent()

    @Serializable
    @SerialName("all_messages_deleted")
    data object AllMessagesDeleted : MessagesWsEvent()

    @Serializable
    @SerialName("chat_deleted")
    data object ChatDeleted : MessagesWsEvent()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String
    ) : MessagesWsEvent()
}


fun Routing.configureChatWebSocketRouting(
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
    chatRepository: ChatRepository,
    mapper: Mapper
) {
    webSocket("/chats/{chatId}/ws") {
        var collectionJob: Job? = null
        var pingPongManager: PingPongManager? = null
        try {
            val sessionDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: Exception) {
                return@webSocket call.respond(HttpStatusCode.Unauthorized, e.message.toString())
            }

            val chatId = call.parameters["chatId"] ?: throw IllegalArgumentException("Missing chatId parameter")
            val chat = chatRepository.getChatById(chatId) ?: throw NotFoundException("Chat not found")
            if (chat.userId != sessionDbo.userId) {
                throw Exception("You don't have access to this chat")
            }

            collectionJob = collectChatChanges(
                wsSession = this,
                chatRepository = chatRepository,
                messageRepository = messageRepository,
                chatId = chatId,
                mapper = mapper
            )

            pingPongManager = PingPongManager(webSocketSession = this)

            incoming.consumeEach { frame ->
                try {
                    pingPongManager.updateActivity()
                    val request = defaultJson.decodeFromString<MessagesWsRequest>((frame as Frame.Text).readText())
                    processWebSocketRequest(
                        request = request,
                        chatId = chatId,
                        messageRepository = messageRepository,
                        chatRepository = chatRepository,
                    )
                } catch (e: ClosedReceiveChannelException) {
                    throw e
                } catch (e: Exception) {
                    application.log.error(e)
                    sendEvent(MessagesWsEvent.Error(message = "Error processing request: ${e.message}"))
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // Normal close, no need to do anything
        } catch (e: Exception) {
            application.log.error(e)
            sendEvent(MessagesWsEvent.Error(message = "Internal server error"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message ?: "Unknown error"))
        } finally {
            collectionJob?.cancel()
            pingPongManager?.stop()
        }
    }
}

private fun CoroutineScope.collectChatChanges(
    wsSession: WebSocketSession,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    chatId: String,
    mapper: Mapper
): Job {
    return launch {

        // Create the deletion buffer for messages
        val deletionBuffer = InactivityBuffer(
            inactivityTimeoutMs = 50,
            checkIntervalMs = 20,
            onBatchReady = { messageIds ->
                wsSession.sendEvent(MessagesWsEvent.MessageRangeDeleted(messageIds = messageIds))
            }
        )

        // Handle chat deletions
        launch {
            chatRepository.collectDeletionEventsForChat(chatId).collect {
                wsSession.sendEvent(MessagesWsEvent.ChatDeleted)
            }
        }

        // Handle message events with batching for deletions
        launch {
            messageRepository.collectAllEventsForChatId(chatId)
                // Split the flow into different types
                .collect { event ->
                    when (event) {
                        is ChangeEvent.Created ->
                            wsSession.sendEvent(
                                MessagesWsEvent.NewMessage(
                                    message = event.new.toMessageDto(mapper)
                                )
                            )

                        is ChangeEvent.Updated ->
                            wsSession.sendEvent(
                                MessagesWsEvent.MessageEdited(
                                    message = event.new.toMessageDto(mapper)
                                )
                            )

                        is ChangeEvent.Deleted -> {
                            deletionBuffer.add(event.old.id)
                        }
                    }
                }
        }

        try {
            awaitCancellation()
        } finally {
            deletionBuffer.close()
        }
    }
}

private suspend fun WebSocketSession.processWebSocketRequest(
    request: MessagesWsRequest,
    chatId: String,
    messageRepository: MessageRepository,
    chatRepository: ChatRepository,
) {
    when (request) {
        is MessagesWsRequest.SendMessage -> {
            val chatDbo = chatRepository.getChatById(chatId)
                ?: return sendEvent(MessagesWsEvent.Error(message = "Chat not found"))

            if (messageRepository.getLastMessageInChat(chatId)?.isLoading == true) {
                return sendEvent(MessagesWsEvent.Error(message = "You can't send new messages yet."))
            }

            if (request.text.isBlank()) {
                return sendEvent(MessagesWsEvent.Error(message = "You can't send empty text."))
            }

            if (request.text.length > 1000) {
                return sendEvent(MessagesWsEvent.Error(message = "You exceed the message limit."))
            }

            messageRepository.addMessage(
                chatId = chatId,
                isSentByUser = true,
                isLoading = false,
                isFailed = false,
                content = request.text
            )

            val characterMessageDbo = messageRepository.addMessage(
                chatId = chatId,
                isSentByUser = false,
                isLoading = true,
                isFailed = false,
                content = ""
            )

            runCatching {
                AiMessageGeneratorUtil.generateAiMessage(
                    characterName = chatDbo.characterName,
                    characterPrompt = chatDbo.characterPrompt,
                    messagesHistory = messageRepository.getMessagesByChatId(chatId = chatId, limit = 50, skip = 0)
                )
            }.onSuccess { message ->
                messageRepository.updateMessage(messageId = characterMessageDbo.id, isLoading = false, text = message)
            }.onFailure { e ->
                messageRepository.updateMessage(messageId = characterMessageDbo.id, isLoading = false, isFailed = true)
                sendEvent(MessagesWsEvent.Error(message = "Couldn't generate ai message: ${e.message}"))
                e.printStackTrace()
            }
        }

        is MessagesWsRequest.ReloadMessage -> {
            val chatDbo = chatRepository.getChatById(chatId)
                ?: return sendEvent(MessagesWsEvent.Error(message = "Chat not found"))
            val messageDbo = messageRepository.getMessageById(request.messageId)
                ?: return sendEvent(MessagesWsEvent.Error(message = "Message not found"))
            if (messageDbo.chatId != chatId) {
                return sendEvent(MessagesWsEvent.Error(message = "You don't have access to reload this message"))
            }
            if (messageDbo.isSentByUser) {
                return sendEvent(MessagesWsEvent.Error(message = "Cannot reload user's message"))
            }
            if (!messageDbo.isFailed) {
                return sendEvent(MessagesWsEvent.Error(message = "Cannot reload successful message"))
            }

            runCatching {
                AiMessageGeneratorUtil.generateAiMessage(
                    characterName = chatDbo.characterName,
                    characterPrompt = chatDbo.characterPrompt,
                    messagesHistory = messageRepository.getMessagesByChatId(chatId = chatId, limit = 50, skip = 0)
                )
            }.onSuccess { message ->
                messageRepository.updateMessage(messageId = messageDbo.id, isLoading = false, text = message)
            }.onFailure { e ->
                messageRepository.updateMessage(messageId = messageDbo.id, isLoading = false, isFailed = true)
                sendEvent(MessagesWsEvent.Error(message = "Couldn't generate ai message: ${e.message}"))
                e.printStackTrace()
            }
        }

        is MessagesWsRequest.EditMessage -> {
            chatRepository.getChatById(chatId) ?: return sendEvent(MessagesWsEvent.Error(message = "Chat not found"))
            val messageDbo = messageRepository.getMessageById(request.messageId)
                ?: return sendEvent(MessagesWsEvent.Error(message = "Message not found"))
            if (messageDbo.chatId != chatId) {
                return sendEvent(MessagesWsEvent.Error(message = "You don't have access to edit this message"))
            }
            if (!messageDbo.isSentByUser) {
                return sendEvent(MessagesWsEvent.Error(message = "Cannot edit character messages"))
            }
            if (request.text.isBlank()) {
                return sendEvent(MessagesWsEvent.Error(message = "You can't send empty text."))
            }
            if (request.text.length > 1000) {
                return sendEvent(MessagesWsEvent.Error(message = "You exceed the message limit."))
            }
            messageRepository.updateMessage(messageId = request.messageId, text = request.text)
        }

        is MessagesWsRequest.MarkAsRead -> {
            chatRepository.getChatById(chatId) ?: return sendEvent(MessagesWsEvent.Error(message = "Chat not found"))
            val messageToMark = messageRepository.getMessageById(request.messageId)
                ?: return sendEvent(MessagesWsEvent.Error(message = "Message not found"))
            if (messageToMark.chatId != chatId) {
                return sendEvent(MessagesWsEvent.Error(message = "You don't have access to this message"))
            }
            if (messageToMark.isSentByUser) {
                return sendEvent(MessagesWsEvent.Error(message = "Cannot mark user messages as read"))
            }
            messageRepository.markMessageAsRead(request.messageId)
        }

        is MessagesWsRequest.MarkAsReadAll -> {
            chatRepository.getChatById(chatId) ?: return sendEvent(MessagesWsEvent.Error(message = "Chat not found"))
            messageRepository.markAllCharacterMessagesAsRead(chatId)
        }

        is MessagesWsRequest.DeleteMessage -> {
            chatRepository.getChatById(chatId) ?: return sendEvent(MessagesWsEvent.Error(message = "Chat not found"))
            val messageToDelete = messageRepository.getMessageById(request.messageId)
                ?: return sendEvent(MessagesWsEvent.Error(message = "Message not found"))
            if (messageToDelete.chatId != chatId) {
                return sendEvent(MessagesWsEvent.Error(message = "You don't have access to delete this message"))
            }
            messageRepository.deleteMessage(request.messageId)
        }

        is MessagesWsRequest.DeleteMessageRange -> {
            chatRepository.getChatById(chatId) ?: return sendEvent(MessagesWsEvent.Error(message = "Chat not found"))
            if (!messageRepository.doAllMessagesBelongToChat(request.messageIds, chatId)) {
                return sendEvent(MessagesWsEvent.Error(message = "Some ids don't belong to this chat."))
            }
            messageRepository.deleteMessagesByIds(request.messageIds)
        }

        is MessagesWsRequest.DeleteAllMessages -> {
            chatRepository.getChatById(chatId) ?: return sendEvent(MessagesWsEvent.Error(message = "Chat not found"))
            messageRepository.deleteAllMessagesInChat(chatId)
        }
    }
}

private suspend fun WebSocketSession.sendEvent(event: MessagesWsEvent) {
    send(Frame.Text(defaultJson.encodeToString(event)))
}