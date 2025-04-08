package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.toMessageDto
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.db.repositories.content.MessageRepository
import com.lvsmsmch.aichat.network.dto_objects.MessageDto
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import io.ktor.server.plugins.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
sealed class WebSocketMessage {
    abstract val type: String
}

@Serializable
data class SendMessageRequest(
    override val type: String = "send_message",
    val chatId: String,
    val text: String
) : WebSocketMessage()

@Serializable
data class EditMessageRequest(
    override val type: String = "edit_message",
    val messageId: String,
    val text: String
) : WebSocketMessage()

@Serializable
data class DeleteMessageRequest(
    override val type: String = "delete_message",
    val messageId: String
) : WebSocketMessage()

@Serializable
data class MarkAsReadRequest(
    override val type: String = "mark_as_read",
    val messageId: String
) : WebSocketMessage()

@Serializable
data class LoadMessagesRequest(
    override val type: String = "load_messages",
    val chatId: String,
    val limit: Int = 50,
    val skip: Int = 0
) : WebSocketMessage()

@Serializable
data class CharacterTypingEvent(
    override val type: String = "character_typing",
    val chatId: String,
    val isTyping: Boolean
) : WebSocketMessage()

@Serializable
data class MessageEvent(
    override val type: String = "message",
    val message: MessageDto
) : WebSocketMessage()

@Serializable
data class MessagesLoadedEvent(
    override val type: String = "messages_loaded",
    val messages: List<MessageDto>,
    val chatId: String,
    val totalCount: Long
) : WebSocketMessage()

@Serializable
data class MessageDeletedEvent(
    override val type: String = "message_deleted",
    val messageId: String,
    val chatId: String
) : WebSocketMessage()

@Serializable
data class MessageEditedEvent(
    override val type: String = "message_edited",
    val message: MessageDto
) : WebSocketMessage()

@Serializable
data class ErrorEvent(
    override val type: String = "error",
    val code: String,
    val message: String
) : WebSocketMessage()


private val chatSessions = ConcurrentHashMap<String, WebSocketSession>()

fun Routing.configureChatWebSocketRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    json: Json,
    mapper: Mapper
) {
    webSocket("/chats/{chatId}/ws") {
        var chatId: String? = null

        try {
            val sessionDbo = sessionRepository.verifyToken(call)

            // Extract chatId from path parameters instead of query parameters
            chatId = call.parameters["chatId"]
                ?: throw IllegalArgumentException("Missing chatId parameter")

            val chat = chatRepository.getChatById(chatId) ?: throw NotFoundException("Chat not found")

            if (chat.userId != sessionDbo.userId) {
                throw UnauthorizedException("You don't have access to this chat")
            }
            chatSessions[chatId] = this

            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val message = frame.readText()
                    processWebSocketMessage(message, chatId, sessionDbo.userId, json, messageRepository, mapper)
                }
            }
        } catch (e: UnauthorizedException) {
            sendErrorMessage("auth_error", e.message, json)
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message))
        } catch (e: IllegalArgumentException) {
            sendErrorMessage("invalid_request", e.message ?: "Invalid request", json)
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message ?: "Invalid request"))
        } catch (e: ClosedReceiveChannelException) {
            // Normal close, no need to do anything
        } catch (e: Exception) {
            sendErrorMessage("server_error", "An error occurred", json)
        } finally {
            // Remove connection when closed
            if (chatId != null) {
                chatSessions.remove(chatId)
            }
        }
    }
}

private suspend fun WebSocketSession.processWebSocketMessage(
    message: String,
    chatId: String,
    userId: String,
    json: Json,
    messageRepository: MessageRepository,
    mapper: Mapper
) {
    try {
        // Parse the message to get the type
        val jsonObj = JsonObject(json.parseToJsonElement(message).jsonObject)
        val type = jsonObj["type"]?.toString()?.replace("\"", "") ?: "unknown"

        when (type) {
            "send_message" -> {
                val request = json.decodeFromString<SendMessageRequest>(message)

                // Validate that the chat IDs match
                if (request.chatId != chatId) {
                    sendErrorMessage("invalid_request", "Chat ID mismatch", json)
                    return
                }

                // Add user message to the database
                val userMessage = messageRepository.addMessage(
                    chatId = chatId,
                    isSentByUser = true,
                    content = request.text
                )

                // Send back the user message confirmation
                sendMessage(MessageEvent(
                    message = userMessage.toMessageDto(mapper),
                ), json)

                // Simulate character "typing"
                sendMessage(CharacterTypingEvent(
                    chatId = chatId,
                    isTyping = true
                ), json)

                // Simulate AI thinking/typing time
                delay(Random.nextLong(1000, 3000))

                // Send stopped typing
                sendMessage(CharacterTypingEvent(
                    chatId = chatId,
                    isTyping = false
                ), json)

                // Generate AI response
                val aiMessage = messageRepository.generateAiResponse(chatId, request.text)

                // Send the AI response
                sendMessage(MessageEvent(
                    message = aiMessage.toMessageDto(mapper),
                ), json)
            }

            "edit_message" -> {
                val request = json.decodeFromString<EditMessageRequest>(message)

                // Check if message exists and belongs to user
                val originalMessage = messageRepository.getMessageById(request.messageId)
                if (originalMessage == null) {
                    sendErrorMessage("not_found", "Message not found", json)
                    return
                }

                // Verify chat ID matches
                if (originalMessage.chatId != chatId) {
                    sendErrorMessage("access_denied", "You don't have access to edit this message", json)
                    return
                }

                // Verify it's a user message (only users can edit their messages)
                if (!originalMessage.isSentByUser) {
                    sendErrorMessage("invalid_request", "Cannot edit character messages", json)
                    return
                }

                // Perform the edit
                val success = messageRepository.editMessage(request.messageId, request.text)
                if (success) {
                    // Fetch the updated message and send confirmation
                    val updatedMessage = messageRepository.getMessageById(request.messageId)
                    if (updatedMessage != null) {
                        sendMessage(MessageEditedEvent(
                            message = updatedMessage.toMessageDto(mapper)
                        ), json)
                    }
                } else {
                    sendErrorMessage("update_failed", "Failed to update message", json)
                }
            }

            "delete_message" -> {
                val request = json.decodeFromString<DeleteMessageRequest>(message)

                // Check if message exists
                val messageToDelete = messageRepository.getMessageById(request.messageId)
                if (messageToDelete == null) {
                    sendErrorMessage("not_found", "Message not found", json)
                    return
                }

                // Verify chat ID matches
                if (messageToDelete.chatId != chatId) {
                    sendErrorMessage("access_denied", "You don't have access to delete this message", json)
                    return
                }

                // Verify it's a user message (only users can delete their messages)
                if (!messageToDelete.isSentByUser) {
                    sendErrorMessage("invalid_request", "Cannot delete character messages", json)
                    return
                }

                // Perform the delete
                val success = messageRepository.deleteMessage(request.messageId)
                if (success) {
                    sendMessage(MessageDeletedEvent(
                        messageId = request.messageId,
                        chatId = chatId
                    ), json)
                } else {
                    sendErrorMessage("delete_failed", "Failed to delete message", json)
                }
            }

            "mark_as_read" -> {
                val request = json.decodeFromString<MarkAsReadRequest>(message)

                // Check if message exists
                val messageToMark = messageRepository.getMessageById(request.messageId)
                if (messageToMark == null) {
                    sendErrorMessage("not_found", "Message not found", json)
                    return
                }

                // Verify chat ID matches
                if (messageToMark.chatId != chatId) {
                    sendErrorMessage("access_denied", "You don't have access to this message", json)
                    return
                }

                // Only mark character messages as read
                if (messageToMark.isSentByUser) {
                    sendErrorMessage("invalid_request", "Cannot mark user messages as read", json)
                    return
                }

                // Mark as read
                messageRepository.markMessageAsRead(request.messageId)
                // No response needed for this operation
            }

            "load_messages" -> {
                val request = json.decodeFromString<LoadMessagesRequest>(message)

                // Validate that the chat IDs match
                if (request.chatId != chatId) {
                    sendErrorMessage("invalid_request", "Chat ID mismatch", json)
                    return
                }

                // Get messages with pagination
                val messages = messageRepository.getMessagesByChatId(
                    chatId = chatId,
                    limit = request.limit,
                    skip = request.skip,
                    descending = false // Get oldest first
                )

                // Get total count for pagination
                val totalCount = messageRepository.countMessagesByChatId(chatId)

                // Map messages to DTOs
                val messageDtos = messages.map { it.toMessageDto(mapper) }

                // Send messages
                sendMessage(MessagesLoadedEvent(
                    messages = messageDtos,
                    chatId = chatId,
                    totalCount = totalCount
                ), json)

                // Mark any unread character messages as read
                messageRepository.markAllCharacterMessagesAsRead(chatId)
            }

            else -> {
                sendErrorMessage("unknown_message_type", "Unknown message type: $type", json)
            }
        }
    } catch (e: Exception) {
        // Handle any errors during message processing
        sendErrorMessage("request_error", "Error processing request: ${e.message}", json)
    }
}

private suspend fun WebSocketSession.sendMessage(message: WebSocketMessage, json: Json) {
    send(Frame.Text(json.encodeToString(message)))
}

private suspend fun WebSocketSession.sendErrorMessage(code: String, message: String, json: Json) {
    sendMessage(ErrorEvent(code = code, message = message), json)
}