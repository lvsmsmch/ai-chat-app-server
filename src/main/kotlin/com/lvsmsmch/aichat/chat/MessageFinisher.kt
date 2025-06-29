package com.lvsmsmch.aichat.chat

import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.chat.database.MessageStatus
import com.lvsmsmch.aichat.chat.network.TestAiMessageGeneratorUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.withTimeout // ✅ Правильно
import kotlin.time.Duration.Companion.seconds

class MessageFinisher(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentlyFinishingMessages = mutableMapOf<String, Job>()

    fun finishMessageAsync(messageId: String, timeoutSeconds: Int = 30) {
        currentlyFinishingMessages[messageId]?.cancel()
        currentlyFinishingMessages[messageId] = scope.launch {
            try {
                val messageDbo = messageRepository.getMessageById(messageId) ?: return@launch
                val chatDbo = chatRepository.getChatById(messageDbo.chatId) ?: return@launch
                val characterDbo = characterRepository.getCharacter(messageDbo.senderId) ?: return@launch
                val messageHistory = messageRepository.getMessagesCreatedBefore(
                    chatId = chatDbo.id,
                    timestamp = messageDbo.createdAt
                ).takeLast(50)

                messageRepository.updateMessage(
                    messageId = messageId,
                    status = MessageStatus.STREAMING.value
                )

                withTimeout(timeoutSeconds.seconds) {
                    TestAiMessageGeneratorUtil.generateAiMessageWithStreaming(
                        characterDbo = characterDbo,
                        messagesHistory = messageHistory,
                        onChunk = {
                            ensureActive()
                            messageRepository.updateMessage(
                                messageId = messageId,
                                text = it,
                                status = MessageStatus.STREAMING.value
                            )
                        },
                        onFinished = {
                            ensureActive()
                            messageRepository.updateMessage(
                                messageId = messageId,
                                text = it,
                                status = MessageStatus.COMPLETED.value
                            )
                        },
                        onError = {
                            ensureActive()
                            messageRepository.updateMessage(
                                messageId = messageId,
                                text = it,
                                status = MessageStatus.FAILED.value
                            )
                        }
                    )
                }
            } catch (e: TimeoutCancellationException) {
                messageRepository.updateMessage(
                    messageId = messageId,
                    text = "",
                    status = MessageStatus.FAILED.value
                )
            } catch (e: Exception) {
                messageRepository.updateMessage(
                    messageId = messageId,
                    text = "",
                    status = MessageStatus.FAILED.value
                )
            } finally {
                currentlyFinishingMessages.remove(messageId)
            }
        }
    }
}