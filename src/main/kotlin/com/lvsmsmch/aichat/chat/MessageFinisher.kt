package com.lvsmsmch.aichat.chat

import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.chat.database.MessageStatus
import com.lvsmsmch.aichat.chat.network.AiMessageGeneratorUtil
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class MessageFinisher(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val currentlyFinishingMessages = ConcurrentHashMap<String, Job>()

    fun isFinishing(messageId: String): Boolean {
        return currentlyFinishingMessages[messageId]?.isActive == true
    }

    fun finishMessageAsync(messageId: String, timeoutSeconds: Int = 30) {
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val messageDbo = messageRepository.getMessageById(messageId) ?: return@launch
                val chatDbo = chatRepository.getChatById(messageDbo.chatId) ?: return@launch
                val characterDbo = characterRepository.getCharacter(messageDbo.senderId) ?: return@launch
                val messageHistory = messageRepository.getMessagesCreatedBefore(
                    chatId = chatDbo.id,
                    timestamp = UtcTimestamp.parse(messageDbo.createdAt)
                ).takeLast(200)
                val participants = chatDbo.characterIds.mapNotNull { characterRepository.getCharacter(it) }

                messageRepository.updateMessage(
                    messageId = messageId,
                    text = "",
                    status = MessageStatus.STREAMING.value
                )

                withTimeout(timeoutSeconds.seconds) {
                    AiMessageGeneratorUtil.generateAiMessageWithStreaming(
                        chatDbo = chatDbo,
                        characterDbo = characterDbo,
                        participants = participants,
                        messagesHistory = messageHistory,
                        onMsgTextUpdate = {
                            ensureActive()
                            messageRepository.updateMessage(
                                messageId = messageId,
                                text = it,
                                status = MessageStatus.STREAMING.value
                            )
                        },
                        onFinished = {
                            logger.debug("finishMessageAsync onFinished, upd msg")
                            ensureActive()
                            messageRepository.updateMessage(
                                messageId = messageId,
                                text = it,
                                status = MessageStatus.COMPLETED.value
                            )
                        },
                        onError = {
                            logger.debug("finishMessageAsync onError, upd msg (${it})")
                            ensureActive()
                            messageRepository.updateMessage(
                                messageId = messageId,
                                text = "",
                                status = MessageStatus.FAILED.value
                            )
                        }
                    )
                }
            } catch (e: TimeoutCancellationException) {
                logger.debug("finishMessageAsync error (timeout), upd msg (${e.message})")
                messageRepository.updateMessage(
                    messageId = messageId,
                    text = "",
                    status = MessageStatus.FAILED.value
                )
            } catch (e: CancellationException) {
                logger.debug("finishMessageAsync cancelled, another job took over (${e.message})")
                throw e
            } catch (e: Exception) {
                logger.debug("finishMessageAsync error, upd msg (${e.message})")
                messageRepository.updateMessage(
                    messageId = messageId,
                    text = "",
                    status = MessageStatus.FAILED.value
                )
            }
        }

        newJob.invokeOnCompletion {
            currentlyFinishingMessages.remove(messageId, newJob)
        }

        currentlyFinishingMessages.put(messageId, newJob)?.cancel()
        newJob.start()
    }
}