package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.toMessageDto
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.CharacterRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.db.repositories.content.MessageRepository
import com.lvsmsmch.aichat.network.dto_objects.MessageDto
import com.lvsmsmch.aichat.utils.AiMessageGeneratorUtil
import com.lvsmsmch.aichat.utils.defaultJson
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import io.ktor.server.plugins.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
sealed class ChatWsRequest {

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
    ) : ChatWsRequest()

    @Serializable
    @SerialName("edit_message")
    data class EditMessage(
        val messageId: String,
        val text: String
    ) : ChatWsRequest()

    @Serializable
    @SerialName("delete_message")
    data class DeleteMessage(
        val messageId: String
    ) : ChatWsRequest()

    @Serializable
    @SerialName("mark_as_read")
    data class MarkAsRead(
        val messageId: String
    ) : ChatWsRequest()

    @Serializable
    @SerialName("mark_as_read_all")
    object MarkAsReadAll : ChatWsRequest()
}


@Serializable
sealed class ChatWsEvent {

    /**
     *  {
     *      "type": "character_typing",
     *      "chatId": "chat_12345",
     *      "isTyping": true
     *  }
     */

    @Serializable
    @SerialName("character_typing")
    data class CharacterTyping(
        val chatId: String,
        val isTyping: Boolean
    ) : ChatWsEvent()

    @Serializable
    @SerialName("new_message")
    data class NewMessage(
        val message: MessageDto
    ) : ChatWsEvent()

    @Serializable
    @SerialName("message_deleted")
    data class MessageDeleted(
        val messageId: String
    ) : ChatWsEvent()

    @Serializable
    @SerialName("message_edited")
    data class MessageEdited(
        val message: MessageDto
    ) : ChatWsEvent()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String
    ) : ChatWsEvent()
}


fun Routing.configureChatWebSocketRouting(
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
    chatRepository: ChatRepository,
    characterRepository: CharacterRepository,
    mapper: Mapper
) {
    val chatSessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()
    webSocket("/chats/{chatId}/ws") {
        var chatId: String? = null

        try {
            val sessionDbo = sessionRepository.verifyToken(call)
            chatId = call.parameters["chatId"] ?: throw IllegalArgumentException("Missing chatId parameter")
            val chat = chatRepository.getChatById(chatId) ?: throw NotFoundException("Chat not found")
            if (chat.userId != sessionDbo.userId) {
                throw Exception("You don't have access to this chat")
            }
            chatSessions.computeIfAbsent(chatId) { ConcurrentHashMap.newKeySet() }.add(this)
            incoming.consumeEach { frame ->
                try {
                    if (frame !is Frame.Text) {
                        return@consumeEach
                    }
                    val request = defaultJson.decodeFromString<ChatWsRequest>(frame.readText())

                    processWebSocketRequest(
                        request = request,
                        chatId = chatId,
                        messageRepository = messageRepository,
                        chatRepository = chatRepository,
                        characterRepository = characterRepository,
                        mapper = mapper
                    )
                } catch (e: ClosedReceiveChannelException) {
                    throw e
                } catch (e: Exception) {
                    // No need to close whole connection after single error
                    sendEvent(ChatWsEvent.Error(message = "Error processing request: ${e.message}"))
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // Normal close, no need to do anything
        } catch (e: Exception) {
            sendEvent(ChatWsEvent.Error(message = "Internal server error"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message ?: "Unknown error"))
        } finally {
            if (chatId != null) {
                chatSessions[chatId]?.remove(this)
                if (chatSessions[chatId]?.isEmpty() == true) {
                    chatSessions.remove(chatId)
                }
            }
        }
    }
}

private suspend fun WebSocketSession.processWebSocketRequest(
    request: ChatWsRequest,
    chatId: String,
    messageRepository: MessageRepository,
    chatRepository: ChatRepository,
    characterRepository: CharacterRepository,
    mapper: Mapper
) {
    when (request) {
        is ChatWsRequest.SendMessage -> {
            val userMessage = messageRepository.addMessage(
                chatId = chatId, isSentByUser = true, content = request.text
            )
            sendEvent(ChatWsEvent.NewMessage(message = userMessage.toMessageDto(mapper)))
            sendEvent(ChatWsEvent.CharacterTyping(chatId = chatId, isTyping = true))
            val messagesHistory = messageRepository.getMessagesByChatId(chatId = chatId, limit = 50, skip = 0)
            val chatDbo = chatRepository.getChatById(chatId)
                ?: return sendEvent(ChatWsEvent.Error(message = "Chat not found"))
            val characterDbo = characterRepository.getCharacter(chatDbo.characterId)
                ?: return sendEvent(ChatWsEvent.Error(message = "Character not found"))
            val characterResponse = try {
                AiMessageGeneratorUtil.generateAiMessage(
                    characterName = characterDbo.name,
                    characterPrompt = characterDbo.prompt,
                    messagesHistory = messagesHistory
                )
            } catch (e: Exception) {
                return sendEvent(ChatWsEvent.Error(message = "Couldn't generate ai message: ${e.message}"))
            }
            val characterMessage = messageRepository.addMessage(
                chatId = chatId, isSentByUser = false, content = characterResponse
            )
            sendEvent(ChatWsEvent.CharacterTyping(chatId = chatId, isTyping = false))
            sendEvent(ChatWsEvent.NewMessage(message = characterMessage.toMessageDto(mapper)))
        }

        is ChatWsRequest.EditMessage -> {
            val originalMessage = messageRepository.getMessageById(request.messageId)
                ?: return sendEvent(ChatWsEvent.Error(message = "Message not found"))
            if (originalMessage.chatId != chatId) {
                return sendEvent(ChatWsEvent.Error(message = "You don't have access to edit this message"))
            }
            if (!originalMessage.isSentByUser) {
                return sendEvent(ChatWsEvent.Error(message = "Cannot edit character messages"))
            }
            val success = messageRepository.editMessage(request.messageId, request.text)
            if (!success) {
                return sendEvent(ChatWsEvent.Error(message = "Failed to update message"))
            }
            val updatedMessage = messageRepository.getMessageById(request.messageId)
                ?: return sendEvent(ChatWsEvent.Error(message = "Internal server error"))
            sendEvent(ChatWsEvent.MessageEdited(message = updatedMessage.toMessageDto(mapper)))
        }

        is ChatWsRequest.DeleteMessage -> {
            val messageToDelete = messageRepository.getMessageById(request.messageId)
                ?: return sendEvent(ChatWsEvent.Error(message = "Message not found"))
            if (messageToDelete.chatId != chatId) {
                return sendEvent(ChatWsEvent.Error(message = "You don't have access to delete this message"))
            }
            if (!messageToDelete.isSentByUser) {
                return sendEvent(ChatWsEvent.Error(message = "Cannot delete character messages"))
            }
            val success = messageRepository.deleteMessage(request.messageId)
            if (!success) {
                return sendEvent(ChatWsEvent.Error(message = "Failed to delete message"))
            }
            sendEvent(ChatWsEvent.MessageDeleted(messageId = request.messageId))
        }

        is ChatWsRequest.MarkAsRead -> {
            val messageToMark = messageRepository.getMessageById(request.messageId)
                ?: return sendEvent(ChatWsEvent.Error(message = "Message not found"))
            if (messageToMark.chatId != chatId) {
                return sendEvent(ChatWsEvent.Error(message = "You don't have access to this message"))
            }
            if (messageToMark.isSentByUser) {
                return sendEvent(ChatWsEvent.Error(message = "Cannot mark user messages as read"))
            }
            messageRepository.markMessageAsRead(request.messageId)
        }

        is ChatWsRequest.MarkAsReadAll -> {
            messageRepository.markAllCharacterMessagesAsRead(chatId)
        }
    }
}

private suspend fun WebSocketSession.sendEvent(event: ChatWsEvent) {
    send(Frame.Text(defaultJson.encodeToString(event)))
}