package com.lvsmsmch.aichat.chat

import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.character.database.localized
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
    private val userRepository: com.lvsmsmch.aichat.user.database.UserRepository
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
                // Владелец чата: язык ответов + тир модели (умный даунгрейд)
                val owner = userRepository.getUserById(chatDbo.userId)
                val lang = owner?.characterLanguage ?: "en"
                val participants = chatDbo.characterIds
                    .mapNotNull { characterRepository.getCharacter(it) }
                    .map { it.localized(lang) }

                messageRepository.updateMessage(
                    messageId = messageId,
                    text = "",
                    status = MessageStatus.STREAMING.value
                )

                // Сообщение-изображение: отдельный генератор, свой таймаут (90с)
                if (messageDbo.isImage) {
                    // Топ-модель до месячного порога, дальше — mid до конца месяца
                    val useTop = (owner?.monthlyTopImageCount ?: 0) <
                        com.lvsmsmch.aichat.user.database.UserRepository.MONTHLY_TOP_IMAGES_LIMIT
                    withTimeout(90.seconds) {
                        try {
                            val result = com.lvsmsmch.aichat.chat.network.AiImageGeneratorUtil.generateImage(
                                characterDbo = characterDbo.localized(lang),
                                messagesHistory = messageHistory,
                                useTopModel = useTop,
                            )
                            messageRepository.updateMessage(
                                messageId = messageId,
                                imageUrl = result.url,
                                imageDebugInfo = result.debugInfo,
                                text = "",
                                status = MessageStatus.COMPLETED.value,
                            )
                            // Успех тратит дневной лимит изображений
                            userRepository.incrementImageCount(chatDbo.userId, onTopModel = useTop)
                        } catch (e: com.lvsmsmch.aichat.chat.network.CensoredException) {
                            logger.error("Image generation censored: ${e.message}")
                            messageRepository.updateMessage(
                                messageId = messageId,
                                text = "",
                                status = MessageStatus.FAILED.value,
                                failReason = com.lvsmsmch.aichat.chat.network.FailReason.CENSORED,
                            )
                            // Цензура тоже тратит лимит: ретраить запрещёнку бесплатно нельзя
                            userRepository.incrementImageCount(chatDbo.userId, onTopModel = useTop)
                        }
                    }
                    return@launch
                }

                withTimeout(timeoutSeconds.seconds) {
                    AiMessageGeneratorUtil.generateAiMessageWithStreaming(
                        chatDbo = chatDbo,
                        characterDbo = characterDbo.localized(lang),
                        participants = participants,
                        messagesHistory = messageHistory,
                        responseLanguage = lang,
                        ownerDbo = owner,
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
                        onError = { reason ->
                            logger.debug("finishMessageAsync onError, upd msg (${reason})")
                            ensureActive()
                            messageRepository.updateMessage(
                                messageId = messageId,
                                text = "",
                                status = MessageStatus.FAILED.value,
                                failReason = reason,
                            )
                        }
                    )
                }
            } catch (e: TimeoutCancellationException) {
                logger.debug("finishMessageAsync error (timeout), upd msg (${e.message})")
                messageRepository.updateMessage(
                    messageId = messageId,
                    text = "",
                    status = MessageStatus.FAILED.value,
                    failReason = com.lvsmsmch.aichat.chat.network.FailReason.ERROR,
                )
            } catch (e: CancellationException) {
                logger.debug("finishMessageAsync cancelled, another job took over (${e.message})")
                throw e
            } catch (e: Exception) {
                logger.debug("finishMessageAsync error, upd msg (${e.message})")
                messageRepository.updateMessage(
                    messageId = messageId,
                    text = "",
                    status = MessageStatus.FAILED.value,
                    failReason = com.lvsmsmch.aichat.chat.network.FailReason.ERROR,
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