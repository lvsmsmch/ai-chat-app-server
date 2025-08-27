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
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

fun Route.configureChatRouting(
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    characterRepository: CharacterRepository,
    sessionRepository: SessionRepository,
    reportRepository: ReportRepository,
    userRepository: UserRepository,
    idGenerator: IdGenerator,
    messageFinisher: MessageFinisher,
    complexQueryHelper: ComplexQueryHelper,
    mapper: Mapper
) {

    route("/chats") {

        // ========== BATCH СИНХРОНИЗАЦИЯ ЧАТОВ ==========

        /**
         * POST /chats/sync
         * Универсальная batch синхронизация чатов и сообщений
         */
        post("/sync") {
            val userId = sessionRepository.verifyToken(call).userId
            val request = call.receive<BatchSyncRequest>()

            val chatSyncResponses = mutableListOf<ChatSyncResponse>()
            val requestedChatIds = request.chatSyncRequests.map { it.chatId }.toSet()

            val allUserChats = chatRepository.getChatsByUserId(userId)

            // Обрабатываем каждый запрос на синхронизацию
            request.chatSyncRequests.forEach { chatSyncRequest ->
                val chat = chatRepository.getChatByClientId(chatSyncRequest.chatId)

                if (chat != null && chat.userId == userId) {
                    val syncResponse = generateChatSyncResponse(
                        chat = chat,
                        chatSyncRequest = chatSyncRequest,
                        chatRepository = chatRepository,
                        messageRepository = messageRepository,
                        mapper = mapper
                    )

                    // Добавляем response только если есть изменения
                    if (syncResponse.chatUpdatedResponse != null || syncResponse.messageListUpdatedResponse != null) {
                        chatSyncResponses.add(syncResponse)
                    }
                }
            }

            allUserChats
                .filter { it.clientId !in requestedChatIds }
                .forEach { chat ->
                    // Создаем фиктивный запрос для fullSync с очень старой датой
                    val fullSyncRequest = ChatSyncRequest(
                        chatId = chat.clientId,
                        lastChatSyncTimestamp = UtcTimestamp.year1900().toString(),
                        lastMessagesSyncTimestamp = UtcTimestamp.year1900().toString(),
                        oldestLoadedMessageTime = null,
                        newestLoadedMessageTime = null
                    )

                    val syncResponse = generateChatSyncResponse(
                        chat = chat,
                        chatSyncRequest = fullSyncRequest,
                        chatRepository = chatRepository,
                        messageRepository = messageRepository,
                        mapper = mapper
                    )

                    // Для fullSync всегда добавляем, но ограничиваем сообщения
                    if (!chat.isDeleted) {
                        val limitedMessages = messageRepository.getMessagesPaginated(
                            chatId = chat.id,
                            cursor = null,
                            limit = 50
                        )

                        val limitedMessageListResponse = MessageListUpdatedResponse(
                            lastMessagesSyncTimestamp = UtcTimestamp.now().toString(),
                            newMessages = limitedMessages.map { it.toMessageDto(mapper) },
                            updatedMessages = emptyList(),
                            deletedMessageIds = emptyList()
                        )

                        chatSyncResponses.add(
                            ChatSyncResponse(
                                chatId = chat.clientId,
                                chatUpdatedResponse = syncResponse.chatUpdatedResponse,
                                messageListUpdatedResponse = limitedMessageListResponse
                            )
                        )
                    } else {
                        chatSyncResponses.add(syncResponse)
                    }
                }

            call.respondSuccess(
                BatchSyncResponse(
                    chatSyncResponses = chatSyncResponses,
                    limitReachedSignal = userRepository.getHasLimitUntil(userId)?.let {
                        LimitReachedSignal(it.toString())
                    }
                )
            )
        }

        // ========== УПРАВЛЕНИЕ ЧАТАМИ ==========

        post("/new") {
            val userId = sessionRepository.verifyToken(call).userId
            val request = call.receive<CreateChatRequest>()

            chatRepository.getChatByClientId(request.chatId)?.let { _ ->
                throw ForbiddenException("Chat already exists")
            }

            validateCharactersSize(request.characterIds.size)

            val characters = request.characterIds.mapNotNull { charId ->
                characterRepository.getCharacter(charId)
            }

            if (characters.size != request.characterIds.size) {
                throw BadRequestException("Some characters not found")
            }

            request.customName?.let { validateCustomChatName(it) }

            val isDirect = request.characterIds.size == 1

            val isFirstChat = if (isDirect) {
                chatRepository.findChatByUserAndCharacter(userId, request.characterIds.first()) == null
            } else {
                false
            }

            val chatDbo = ChatDbo(
                id = idGenerator.generateId(EntityType.CHAT),
                clientId = request.chatId,
                userId = userId,
                characterIds = request.characterIds,
                customName = request.customName,
                type = if (isDirect) ChatType.DIRECT else ChatType.GROUP,
                isFirstChatWithThisCharacter = isFirstChat
            )

            complexQueryHelper.addChat(chatDbo)

            val limitUntil = userRepository.getHasLimitUntil(userId)
            val shouldAddInitMessage = limitUntil == null

            // Создаем начальное сообщение если указано
            if (request.initialMessageId != null && shouldAddInitMessage) {
                val characterId = request.characterIds.first()
                MessageDbo(
                    id = idGenerator.generateId(EntityType.MESSAGE),
                    chatId = chatDbo.id,
                    chatClientId = chatDbo.clientId,
                    clientId = request.initialMessageId,
                    senderId = characterId,
                    isSentByUser = false,
                    text = "",
                    status = MessageStatus.STREAMING.value,
                ).also {
                    complexQueryHelper.addMessage(it)
                    messageFinisher.finishMessageAsync(it.id)
                }
            }

            // Создаем полный sync response с учетом всех возможных изменений
            val chatSyncRequest = ChatSyncRequest(
                chatId = chatDbo.clientId,
                lastChatSyncTimestamp = UtcTimestamp.year1900().toString(),
                lastMessagesSyncTimestamp = UtcTimestamp.year1900().toString(),
                oldestLoadedMessageTime = null,
                newestLoadedMessageTime = null
            )

            val chatSyncResponse = generateChatSyncResponse(
                chat = chatDbo,
                chatSyncRequest = chatSyncRequest,
                chatRepository = chatRepository,
                messageRepository = messageRepository,
                mapper = mapper
            )

            call.respondSuccess(
                CreateChatResponse(
                    isSuccess = true,
                    addInitMessageSuccess = shouldAddInitMessage,
                    limitReachedSignal = userRepository.getHasLimitUntil(userId)?.let {
                        LimitReachedSignal(it.toString())
                    },
                    chatSyncResponse = chatSyncResponse
                )
            )
        }

        /**
         * GET /chats/{chatId}
         * Получение деталей конкретного чата
         */
        get("/{chatId}") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            val chatDto = chat.toChatDto(mapper)
            call.respondSuccess(chatDto)
        }

        /**
         * PUT /chats/{chatId}
         * Обновление настроек чата
         */
        put("/{chatId}") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")
            val request = call.receive<UpdateChatRequest>()

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            request.customName?.let { validateCustomChatName(it) }

            // Обновляем чат
            chatRepository.updateChat(
                chatId = chat.id,
                customName = request.customName
            )

            val updatedChat = chatRepository.getChatById(chat.id)!!

            // Генерируем полный sync response
            val chatSyncResponse = generateChatSyncResponse(
                chat = updatedChat,
                chatSyncRequest = request.chatSyncRequest,
                chatRepository = chatRepository,
                messageRepository = messageRepository,
                mapper = mapper
            )

            call.respondSuccess(
                UpdateChatResponse(
                    isSuccess = true,
                    chatSyncResponse = chatSyncResponse
                )
            )
        }

        /**
         * DELETE /chats/{chatId}
         * Удаление чата
         */
        delete("/{chatId}") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatClientId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")

            val chat = chatRepository.getChatByClientId(chatClientId)
                ?: throw ChatNotFoundException(chatClientId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            chatRepository.deleteChat(chat.id)

            call.respondSuccess()
        }

        /**
         * DELETE /chats
         * Удаление списка чатов
         */
        post("/delete") {
            val userId = sessionRepository.verifyToken(call).userId
            val request = call.receive<DeleteChatsRequest>()

            if (request.chatIds.isEmpty()) {
                throw BadRequestException("At least one chat ID must be provided")
            }

            if (request.chatIds.size > 100) {
                throw BadRequestException("Cannot delete more than 100 chats at once")
            }

            val chatClientIds = request.chatIds.distinct()
            val chats = chatRepository.getChatsByClientIds(chatClientIds)

            // Проверяем, что все чаты найдены
            val foundChatIds = chats.map { it.clientId }.toSet()
            val notFoundChatIds = chatClientIds.toSet() - foundChatIds
            if (notFoundChatIds.isNotEmpty()) {
                throw BadRequestException("Chats not found: ${notFoundChatIds.joinToString(", ")}")
            }

            // Проверяем, что все чаты принадлежат пользователю
            val unauthorizedChats = chats.filter { it.userId != userId }
            if (unauthorizedChats.isNotEmpty()) {
                val unauthorizedIds = unauthorizedChats.map { it.clientId }
                throw ForbiddenException("Access denied to chats: ${unauthorizedIds.joinToString(", ")}")
            }

            // Удаляем чаты
            chatRepository.deleteChatsByIds(chats.map { it.id })

            call.respondSuccess(
                DeleteChatsResponse(isSuccess = true)
            )
        }

        // ========== СООБЩЕНИЯ ==========

        /**
         * GET /chats/{chatId}/messages
         * Получение сообщений с курсорной пагинацией
         */
        get("/{chatId}/messages") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")

            val request = GetMessagesRequest(
                cursor = call.request.queryParameters["cursor"],
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
            )

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            require(request.size in 1..100) { "Size must be between 1 and 100" }

            val messages = messageRepository.getMessagesPaginated(
                chatId = chat.id,
                cursor = request.cursor,
                limit = request.size + 1
            )

            val hasMore = messages.size > request.size
            val resultMessages = if (hasMore) messages.dropLast(1) else messages

            val nextCursor = if (hasMore) {
                resultMessages.lastOrNull()?.clientId
            } else null

            call.respondSuccess(
                GetMessagesResponse(
                    messages = resultMessages.map { it.toMessageDto(mapper) },
                    nextCursor = nextCursor,
                )
            )
        }

        /**
         * POST /chats/{chatId}/messages
         * Отправка нового сообщения
         */
        post("/{chatId}/messages") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatClientId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")
            val request = call.receive<SendMessageRequest>()

            val chat = chatRepository.getChatByClientId(chatClientId)
                ?: throw ChatNotFoundException(chatClientId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            // Проверяем, не существует ли уже пользовательское сообщение
            request.userMessage?.let { userMessage ->
                val existingMessage = messageRepository.findByClientId(userMessage.id)
                if (existingMessage != null) {
                    val chatSyncResponse = generateChatSyncResponse(
                        chat = chat,
                        chatSyncRequest = request.chatSyncRequest,
                        chatRepository = chatRepository,
                        messageRepository = messageRepository,
                        mapper = mapper
                    )

                    return@post call.respondSuccess(
                        SendMessageResponse(
                            isSuccess = false,
                            chatSyncResponse = chatSyncResponse
                        )
                    )
                }
            }

            val limitUntil = userRepository.getHasLimitUntil(userId)
            if (limitUntil != null) {
                val chatSyncResponse = generateChatSyncResponse(
                    chat = chat,
                    chatSyncRequest = request.chatSyncRequest,
                    chatRepository = chatRepository,
                    messageRepository = messageRepository,
                    mapper = mapper
                )

                call.respondSuccess(
                    SendMessageResponse(
                        isSuccess = false,
                        limitReachedSignal = LimitReachedSignal(limitUntil = limitUntil.toString()),
                        chatSyncResponse = chatSyncResponse
                    )
                )

                return@post
            }

            request.userMessage?.let { userMessage ->
                validateMessageText(userMessage.text)
                MessageDbo(
                    id = idGenerator.generateId(EntityType.MESSAGE),
                    chatId = chat.id,
                    clientId = userMessage.id,
                    senderId = userId,
                    isSentByUser = true,
                    text = userMessage.text,
                    status = MessageStatus.COMPLETED.value,
                    chatClientId = chat.clientId,
                ).also {
                    complexQueryHelper.addMessage(it)
                }
            }

            request.characterMessage?.let { characterMessage ->
                val characterId = characterMessage.characterId
                MessageDbo(
                    id = idGenerator.generateId(EntityType.MESSAGE),
                    chatId = chat.id,
                    clientId = characterMessage.id,
                    senderId = characterId,
                    isSentByUser = false,
                    text = "",
                    status = MessageStatus.STREAMING.value,
                    chatClientId = chat.clientId,
                ).also {
                    complexQueryHelper.addMessage(it)
                    messageFinisher.finishMessageAsync(it.id)
                }
            }

            val chatSyncResponse = generateChatSyncResponse(
                chat = chat,
                chatSyncRequest = request.chatSyncRequest,
                chatRepository = chatRepository,
                messageRepository = messageRepository,
                mapper = mapper
            )

            call.respondSuccess(
                SendMessageResponse(
                    isSuccess = true,
                    limitReachedSignal = userRepository.getHasLimitUntil(userId)?.let {
                        LimitReachedSignal(limitUntil = it.toString())
                    },
                    chatSyncResponse = chatSyncResponse
                )
            )
        }

        /**
         * POST /chats/messages/{messageId}/report
         * Репорт сообщения
         */
        post("/messages/{messageId}/report") {
            val currentUserId = sessionRepository.verifyToken(call).userId
            val messageClientId = call.parameters["id"]
                ?: throw BadRequestException("Missing messageId parameter")
            val request = call.receive<ReportMessageRequest>()

            val messageDbo = messageRepository.findByClientId(messageClientId)
                ?: throw BadRequestException("Message not found")

            reportRepository.addReport(
                ReportDbo(
                    reportedBy = currentUserId,
                    entityType = ReportEntity.Message.code,
                    entityId = messageDbo.id,
                    reason = request.reason,
                    text = request.text
                )
            )

            call.respondSuccess()
        }

        /**
         * DELETE /chats/{chatId}/messages
         * Удаление сообщений
         */
        post("/messages/delete") {
            val userId = sessionRepository.verifyToken(call).userId
            val request = call.receive<DeleteMessagesRequest>()

            logger.debug(">>>")
            logger.debug(">>>")
            logger.debug(">>>")
            logger.debug(">>> /messages/delete")
            logger.debug(">>>")
            logger.debug(">>>")
            logger.debug(">>>")
            logger.debug("request: ${request}")

            val messageClientIds = request.messageIds.distinct()
            val messageDbos = messageRepository.getMessagesByClientIds(messageClientIds)

            // Проверяем, что все сообщения существуют
            if (messageDbos.size != messageClientIds.size) {
                throw BadRequestException("Some messages not found")
            }

            // Проверяем, что все сообщения принадлежат одному чату
            val chatIds = messageDbos.map { it.chatId }.toSet()
            if (chatIds.size != 1) {
                throw BadRequestException("All messages must belong to the same chat")
            }

            val chatId = chatIds.first()
            val chat = chatRepository.getChatById(chatId)
                ?: throw BadRequestException("Chat not found")

            // Проверяем, что чат принадлежит пользователю
            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            // Удаляем сообщения
            logger.debug("delete messages...")
            messageRepository.deleteMessagesByIds(
                chatId = chatId,
                messageIds = messageDbos.map { it.id }
            )
            logger.debug("delete messages done")

            // Используем метод для генерации полного sync response
            val chatSyncResponse = generateChatSyncResponse(
                chat = chat,
                chatSyncRequest = request.chatSyncRequest,
                chatRepository = chatRepository,
                messageRepository = messageRepository,
                mapper = mapper
            )

            logger.debug("Done, chatSyncResponse: $chatSyncResponse")

            call.respondSuccess(
                DeleteMessagesResponse(
                    isSuccess = true,
                    chatSyncResponse = chatSyncResponse
                )
            )
        }


        // ========== SSE СТРИМИНГ ==========

        /**
         * POST /chats/{chatId}/messages/{messageId}/stream
         * Подписка на стрим обновлений сообщения через SSE с поддержкой синхронизации
         */
        post("/{chatId}/messages/{messageId}/stream") {
            logger.info(">>>")
            logger.info(">>>")
            logger.info(">>>")
            logger.info(">>> SSE request")
            logger.info(">>>")
            logger.info(">>>")
            logger.info(">>>")

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

            logger.info("SSE request passed")

            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.header(HttpHeaders.Connection, "keep-alive")
            call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            call.response.header("Cache-Control", "no-cache")
            call.response.header("X-Accel-Buffering", "no")

            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    logger.info("SSE 1")

                    val currentMessage = messageRepository.getMessageById(message.id)
                        ?: return@respondTextWriter
                    logger.info(
                        "SSE 2,current status ${currentMessage.status}"
                    )

//                    val initialChunk = StreamMessageChunk(
//                        chunk = currentMessage.text,
//                        isComplete = currentMessage.status == MessageStatus.COMPLETED.value,
//                        isFailed = currentMessage.status == MessageStatus.FAILED.value,
//                        nsfw = false
//                    )
//
//                    logger.info("SSE 3")
//
//                    write("data: ${defaultJson.encodeToString(initialChunk)}\n\n")
//                    flush()


                    if (currentMessage.status == MessageStatus.COMPLETED.value) {
                        logger.info("SSE 3")
                        val finalSyncResponse = generateChatSyncResponse(
                            chat = chat,
                            chatSyncRequest = request.chatSyncRequest,
                            chatRepository = chatRepository,
                            messageRepository = messageRepository,
                            mapper = mapper
                        )

                        val finalChunk = StreamMessageChunk(
                            chunk = "",
                            isComplete = true,
                            isFailed = false,
                            nsfw = false,
                            chatSyncResponse = finalSyncResponse
                        )

                        try {
                            write("data: ${defaultJson.encodeToString(finalChunk)}\n\n")
                            flush()
                        } catch (writeException: Exception) {
                        }

                        return@respondTextWriter
                    }

                    if (currentMessage.status == MessageStatus.FAILED.value) {
                        logger.info("SSE 4")
//                        messageRepository.updateMessage(
//                            messageId = message.id,
//                            text = "",
//                            status = MessageStatus.STREAMING.value,
//                        )
                        messageFinisher.finishMessageAsync(message.id)
                    }

                    if (!messageFinisher.isFinishing(message.id)) {
                        messageFinisher.finishMessageAsync(message.id)
                    }

                    messageRepository.streamMessageUpdates(message.id)
                        .collect { update ->
//                            logger.info("SSE 10, ${update}")
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
                                    nsfw = false,
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

                            if (update.isComplete || update.isFailed) {
                                return@collect
                            }
                        }
                } catch (e: Exception) {
                    val errorChunk = StreamMessageChunk(
                        chunk = "",
                        isComplete = false,
                        isFailed = true,
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
    }
}


// Вспомогательная функция для генерации ChatSyncResponse
suspend fun generateChatSyncResponse(
    chat: ChatDbo,
    chatSyncRequest: ChatSyncRequest,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    mapper: Mapper
): ChatSyncResponse {

    // Определяем временные метки для синхронизации
    val chatSyncTimestamp = chatSyncRequest.lastChatSyncTimestamp?.let {
        UtcTimestamp.parse(it)
    } ?: UtcTimestamp.year1900()

    val messagesSyncTimestamp = chatSyncRequest.lastMessagesSyncTimestamp?.let {
        UtcTimestamp.parse(it)
    } ?: UtcTimestamp.year1900()

    val oldestLoaded = chatSyncRequest.oldestLoadedMessageTime?.let { UtcTimestamp.parse(it) }
    val newestLoaded = chatSyncRequest.newestLoadedMessageTime?.let { UtcTimestamp.parse(it) }

    // Проверяем изменения чата
    var chatUpdatedResponse: ChatUpdatedResponse? = null
    if (chat.lastModifiedAt > chatSyncTimestamp.toString() || chat.isDeleted) {
        chatUpdatedResponse = ChatUpdatedResponse(
            lastChatSyncTimestamp = UtcTimestamp.now().toString(),
            chat = chat.toChatDto(mapper),
            isChatDeleted = chat.isDeleted
        )
    }

    // Проверяем изменения сообщений
    var messageListUpdatedResponse: MessageListUpdatedResponse? = null

    // Новые сообщения
    val newMessages = if (newestLoaded != null) {
        messageRepository.getMessagesCreatedAfter(chat.id, newestLoaded)
    } else {
        messageRepository.getMessagesCreatedAfter(chat.id, messagesSyncTimestamp)
    }

    // Обновленные сообщения в загруженном диапазоне
    val updatedMessages = if (oldestLoaded != null && newestLoaded != null) {
        messageRepository.getMessagesUpdatedInRange(
            chatId = chat.id,
            afterTimestamp = messagesSyncTimestamp,
            oldestTime = oldestLoaded,
            newestTime = newestLoaded
        )
    } else {
        messageRepository.getMessagesUpdatedAfter(chat.id, messagesSyncTimestamp)
    }

    // Удаленные сообщения в загруженном диапазоне
    val deletedMessageIds = if (oldestLoaded != null && newestLoaded != null) {
        messageRepository.getDeletedMessageIdsInRange(
            chatId = chat.id,
            afterTimestamp = messagesSyncTimestamp,
            oldestTime = oldestLoaded,
            newestTime = newestLoaded
        )
    } else {
        messageRepository.getDeletedMessageIdsAfter(chat.id, messagesSyncTimestamp)
    }

    // Создаем response для сообщений если есть изменения
    if (newMessages.isNotEmpty() || updatedMessages.isNotEmpty() || deletedMessageIds.isNotEmpty()) {
        messageListUpdatedResponse = MessageListUpdatedResponse(
            lastMessagesSyncTimestamp = UtcTimestamp.now().toString(),
            newMessages = newMessages.map { it.toMessageDto(mapper) },
            updatedMessages = updatedMessages.map { it.toMessageDto(mapper) },
            deletedMessageIds = deletedMessageIds
        )
    }

    return ChatSyncResponse(
        chatId = chat.clientId,
        chatUpdatedResponse = chatUpdatedResponse,
        messageListUpdatedResponse = messageListUpdatedResponse
    )
}