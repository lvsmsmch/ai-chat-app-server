package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat._common.database.ReportDbo
import com.lvsmsmch.aichat._common.database.ReportEntity
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.MessageFinisher
import com.lvsmsmch.aichat.chat.database.*
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

/**
 * Генерация: картинка по запросу и SSE-стрим ответа персонажа.
 */
fun Route.configureGenerationRouting(
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    characterRepository: CharacterRepository,
    sessionRepository: SessionRepository,
    reportRepository: ReportRepository,
    userRepository: UserRepository,
    idGenerator: IdGenerator,
    messageFinisher: MessageFinisher,
    mapper: Mapper,
    chatService: com.lvsmsmch.aichat.chat.ChatService,
) {
    route("/chats") {
        post("/{chatId}/generate-image") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")
            val request = call.receive<GenerateImageRequest>()
            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)
            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }
            val user = userRepository.getUserById(userId)
                ?: throw com.lvsmsmch.aichat.utils.UserNotFoundException()
            if (UserRepository.imageLimitsEnforced) {
                if (!user.hasSubscription) {
                    throw ForbiddenException(errorMessage = "image_generation_premium_only")
                }
                if (user.dailyImageCount >= UserRepository.DAILY_IMAGES_PREMIUM) {
                    throw ForbiddenException(errorMessage = "image_limit_reached")
                }
            }
            val characterId = chat.characterIds.firstOrNull()
                ?: throw BadRequestException("Chat has no characters")

            val existing = messageRepository.findByClientId(request.messageId)
            when {
                existing == null -> MessageDbo(
                    id = idGenerator.generateId(EntityType.MESSAGE),
                    chatId = chat.id,
                    clientId = request.messageId,
                    senderId = characterId,
                    isSentByUser = false,
                    text = "",
                    isImage = true,
                    status = MessageStatus.STREAMING.value,
                    chatClientId = chat.clientId,
                ).also {
                    chatService.addMessage(it)
                    messageFinisher.finishMessageAsync(it.id)
                }
                existing.status != MessageStatus.COMPLETED.value &&
                    !messageFinisher.isFinishing(existing.id) ->
                    messageFinisher.finishMessageAsync(existing.id)
            }

            // Ожидаемое время генерации — по тому же выбору провайдера, что
            // сделает MessageFinisher (топ-модель до месячного порога)
            val useTop = user.monthlyTopImageCount <
                com.lvsmsmch.aichat.user.database.UserRepository.MONTHLY_TOP_IMAGES_LIMIT
            call.respondSuccess(
                GenerateImageResponse(
                    isSuccess = true,
                    expectedMs = com.lvsmsmch.aichat.chat.network.ImageGenEta.expectedMs(useTop),
                )
            )
        }

        post("/{chatId}/messages/{messageId}/stream") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")
            val messageId = call.parameters["messageId"]
                ?: throw BadRequestException("Message ID is required")

            val request = call.receive<StreamMessageRequest>()

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            val message = messageRepository.findByClientId(messageId)
                ?: throw BadRequestException("Message not found")

            if (message.chatId != chat.id) {
                throw BadRequestException("Message does not belong to this chat")
            }

            if (message.isSentByUser) {
                throw BadRequestException("Cannot stream user messages")
            }

            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.header(HttpHeaders.Connection, "keep-alive")
            call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            call.response.header("Cache-Control", "no-cache")
            call.response.header("X-Accel-Buffering", "no")

            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    val currentMessage = messageRepository.getMessageById(message.id)
                        ?: return@respondTextWriter

                    if (currentMessage.status == MessageStatus.COMPLETED.value) {
                        val finalSyncResponse = generateChatSyncResponse(
                            chat = chat,
                            chatSyncRequest = request.chatSyncRequest,
                            chatRepository = chatRepository,
                            messageRepository = messageRepository,
                            mapper = mapper
                        )

                        val finalChunk = StreamMessageChunk(
                            chunk = currentMessage.text,
                            isComplete = true,
                            isFailed = false,
                            nsfw = false,
                            imageUrl = currentMessage.imageUrl,
                            imageDebugInfo = currentMessage.imageDebugInfo,
                            chatSyncResponse = finalSyncResponse
                        )

                        try {
                            write("data: ${defaultJson.encodeToString(finalChunk)}\n\n")
                            flush()
                        } catch (writeException: Exception) {
                        }

                        return@respondTextWriter
                    }

                    if (!messageFinisher.isFinishing(message.id)) {
                        messageFinisher.finishMessageAsync(message.id)
                    }

                    // firstOrNull завершает подписку на терминальном чанке (collect с
                    // return@collect висел вечно); таймаут страхует от молчащей генерации —
                    // соединение всегда закрывается, пул клиента не забивается.
                    val terminal = withTimeoutOrNull(180_000L) {
                        messageRepository.streamMessageUpdates(message.id).firstOrNull { update ->
                            val chunk = if (update.isComplete || update.isFailed) {
                                val finalSyncResponse = generateChatSyncResponse(
                                    chat = chat,
                                    chatSyncRequest = request.chatSyncRequest,
                                    chatRepository = chatRepository,
                                    messageRepository = messageRepository,
                                    mapper = mapper
                                )

                                StreamMessageChunk(
                                    chunk = update.newText,
                                    isComplete = update.isComplete,
                                    isFailed = update.isFailed,
                                    failReason = update.failReason,
                                    nsfw = false,
                                    imageUrl = update.imageUrl,
                                    imageDebugInfo = update.imageDebugInfo,
                                    chatSyncResponse = finalSyncResponse
                                )
                            } else {
                                StreamMessageChunk(
                                    chunk = update.newText,
                                    isComplete = false,
                                    isFailed = false,
                                    nsfw = false
                                )
                            }

                            withContext(Dispatchers.IO) {
                                try {
                                    write("data: ${defaultJson.encodeToString(chunk)}\n\n")
                                    flush()
                                } catch (writeException: Exception) {
                                }
                            }

                            update.isComplete || update.isFailed
                        }
                    }

                    if (terminal == null) {
                        val timeoutChunk = StreamMessageChunk(
                            chunk = "",
                            isComplete = false,
                            isFailed = true,
                            failReason = FailReason.ERROR,
                            nsfw = false
                        )
                        try {
                            write("data: ${defaultJson.encodeToString(timeoutChunk)}\n\n")
                            flush()
                        } catch (writeException: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    val errorChunk = StreamMessageChunk(
                        chunk = "",
                        isComplete = false,
                        isFailed = true,
                        failReason = FailReason.ERROR,
                        nsfw = false
                    )

                    try {
                        logger.error("SSE error, ${defaultJson.encodeToString(errorChunk)}\n\n")
                        write("data: ${defaultJson.encodeToString(errorChunk)}\n\n")
                        flush()
                    } catch (writeException: Exception) {
                    }

                    logger.error("Error in SSE stream for message $${message.id}", e)
                }
            }
        }



        rateLimit(RateLimitName("rewarded")) {
            post("/rewarded") {
                val userId = sessionRepository.verifyToken(call).userId
                val request = call.receive<UserRewardedRequest>()

                userRepository.addUserLimitsAfterRewardedWasWatched(userId)
                delay(100)

                call.respondSuccess(
                    UserRewardedResponse(
                        limitsResponse = userRepository.getLimits(userId)
                    )
                )
            }
        }
    }
}
