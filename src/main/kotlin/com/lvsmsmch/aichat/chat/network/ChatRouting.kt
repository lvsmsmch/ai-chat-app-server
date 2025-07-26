// ============= COMPLETE CHAT REST API ROUTING =============

package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.ActivityType
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.MessageFinisher
import com.lvsmsmch.aichat.chat.database.*
import com.lvsmsmch.aichat.utils.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

fun Route.configureChatRouting(
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    characterRepository: CharacterRepository,
    sessionRepository: SessionRepository,
    characterActivityLogRepository: CharacterActivityLogRepository,
    idGenerator: IdGenerator,
    messageFinisher: MessageFinisher,
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
                    chatSyncRequest.updateRequest?.let { updateRequest ->
                        chatRepository.updateChat(
                            chatId = chat.id,
                            customName = updateRequest.customName
                        )
                    }

                    if (chat.isDeleted || chatSyncRequest.isDeleted == true) {
                        if (!chat.isDeleted) {
                            chatRepository.deleteChat(chatSyncRequest.chatId)
                        }

                        chatSyncResponses.add(
                            ChatSyncResponse(
                                chatId = chat.clientId,
                                chat = chatRepository.getChatById(chat.id)!!.toChatDto(mapper),
                                isDeleted = true
                            )
                        )
                        return@forEach
                    }

                    chatSyncRequest.deletedMessageIds?.let { deletedMessageIds ->
                        messageRepository.deleteMessagesByIds(chat.id, deletedMessageIds)
                    }

                    // Логика синхронизации чата
                    val syncTimestamp = chatSyncRequest.lastSyncTimestamp?.let {
                        UtcTimestamp.parse(it)
                    } ?: UtcTimestamp.now().subtractYears(100)

                    val oldestLoaded = chatSyncRequest.oldestLoadedMessageTime?.let { UtcTimestamp.parse(it) }
                    val newestLoaded = chatSyncRequest.newestLoadedMessageTime?.let { UtcTimestamp.parse(it) }

                    // Новые сообщения (добавляем ВСЕ новые, без лимитов для существующих чатов)
                    val newMessages = if (newestLoaded != null) {
                        messageRepository.getMessagesCreatedAfter(chat.id, newestLoaded)
                    } else {
                        messageRepository.getMessagesCreatedAfter(chat.id, syncTimestamp)
                    }

                    // Обновленные сообщения в загруженном диапазоне
                    val updatedMessages = if (oldestLoaded != null && newestLoaded != null) {
                        messageRepository.getMessagesUpdatedInRange(
                            chatId = chat.id,
                            afterTimestamp = syncTimestamp,
                            oldestTime = oldestLoaded,
                            newestTime = newestLoaded
                        )
                    } else {
                        messageRepository.getMessagesUpdatedAfter(chat.id, syncTimestamp)
                    }

                    // Удаленные сообщения в загруженном диапазоне
                    val deletedMessageIds = if (oldestLoaded != null && newestLoaded != null) {
                        messageRepository.getDeletedMessageIdsInRange(
                            chatId = chat.id,
                            afterTimestamp = syncTimestamp,
                            oldestTime = oldestLoaded,
                            newestTime = newestLoaded
                        )
                    } else {
                        messageRepository.getDeletedMessageIdsAfter(chat.id, syncTimestamp)
                    }

                    chatSyncResponses.add(
                        ChatSyncResponse(
                            chatId = chat.clientId,
                            chat = chat.toChatDto(mapper),
                            newMessages = newMessages.map { it.toMessageDto(mapper) },
                            updatedMessages = updatedMessages.map { it.toMessageDto(mapper) },
                            deletedMessageIds = deletedMessageIds,
                            isDeleted = false
                        )
                    )
                }
            }


            if (request.fullSync) {
                allUserChats
                    .filter { it.clientId !in requestedChatIds }
                    .forEach { chat ->
                        if (chat.isDeleted) {
                            chatSyncResponses.add(
                                ChatSyncResponse(
                                    chatId = chat.clientId,
                                    chat = chat.toChatDto(mapper),
                                    isDeleted = true
                                )
                            )

                            return@forEach
                        }

                        val limitedMessages = messageRepository.getMessagesPaginated(
                            chatId = chat.id,
                            cursor = null,
                            limit = 50
                        )

                        chatSyncResponses.add(
                            ChatSyncResponse(
                                chatId = chat.clientId,
                                chat = chat.toChatDto(mapper),
                                newMessages = limitedMessages.map { it.toMessageDto(mapper) },
                                isDeleted = false
                            )
                        )
                    }
            }

            call.respondSuccess(
                BatchSyncResponse(
                    chatSyncResponses = chatSyncResponses
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

            val isDirect = request.characterIds.size == 1

            if (isDirect) {
                val existingChat = chatRepository.findChatByUserAndCharacter(
                    userId, request.characterIds.first()
                )
                if (existingChat != null && !existingChat.isDeleted) {
                    throw BadRequestException("Chat with this character already exists")
                }
            }

            val chatDbo = ChatDbo(
                id = idGenerator.generateId(EntityType.CHAT),
                clientId = request.chatId,
                userId = userId,
                characterIds = request.characterIds,
                type = if (isDirect) ChatType.DIRECT else ChatType.GROUP
            )

            chatRepository.insertChat(chatDbo)

            request.characterIds.forEach { characterId ->
                characterActivityLogRepository.logActivity(
                    activityType = ActivityType.CHAT_CREATED,
                    characterId = characterId,
                    userId = userId
                )
            }


            val initialMessage = if (request.initialMessageId != null) {
                MessageDbo(
                    id = idGenerator.generateId(EntityType.MESSAGE),
                    chatId = chatDbo.id,
                    chatClientId = chatDbo.clientId,
                    clientId = request.initialMessageId,
                    senderId = request.characterIds.first(),
                    isSentByUser = false,
                    text = "",
                    status = MessageStatus.STREAMING.value,
                ).also {
                    messageRepository.insertMessage(it)
                    messageFinisher.finishMessageAsync(it.id)
                }
            } else null


            val chatDto = chatDbo.toChatDto(mapper)

            call.respondSuccess(
                CreateChatResponse(
                    chat = chatDto,
                    initialMessage = initialMessage?.toMessageDto(mapper)
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

            chatRepository.updateChat(
                chatId = chat.id,
                customName = request.customName
            )

            val updatedChat = chatRepository.getChatById(chat.id)!!
            val chatDto = updatedChat.toChatDto(mapper)

            call.respondSuccess(UpdateChatResponse(chat = chatDto))
        }

        /**
         * DELETE /chats/{chatId}
         * Удаление чата
         */
        delete("/{chatId}") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

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

            val chatIds = request.chatIds.distinct()
            val chats = chatRepository.getChatsByClientIds(chatIds)

            // Проверяем, что все чаты найдены
            val foundChatIds = chats.map { it.clientId }.toSet()
            val notFoundChatIds = chatIds.toSet() - foundChatIds
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
            val chatDbIds = chats.map { it.id }
            chatRepository.deleteChatsByIds(chatDbIds)

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

            val prevCursor = resultMessages.firstOrNull()?.clientId

            call.respondSuccess(
                GetMessagesResponse(
                    messages = resultMessages.map { it.toMessageDto(mapper) },
                    nextCursor = nextCursor,
                    prevCursor = prevCursor,
                    hasMore = hasMore
                )
            )
        }

        /**
         * POST /chats/{chatId}/messages
         * Отправка нового сообщения
         */
        post("/{chatId}/messages") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")
            val request = call.receive<SendMessageRequest>()

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            val syncRequired = messageRepository.hasChangesAfter(
                chatId = chat.id,
                timestamp = UtcTimestamp.parse(request.lastChatSyncTimestamp)
            )

            if (syncRequired) {
                return@post call.respondSuccess(SendMessageResponse(syncRequired = true))
            }

            request.userMessage?.let { userMessage ->
                val existingMessage = messageRepository.findByClientId(userMessage.id)
                if (existingMessage != null) {
                    return@post call.respondSuccess(SendMessageResponse(syncRequired = true))
                }
            }

            val newUserMessage = request.userMessage?.let { userMessage ->
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
                    messageRepository.insertMessage(it)
                }
            }

            val newCharacterMessage = request.characterMessage?.let { characterMessage ->
                MessageDbo(
                    id = idGenerator.generateId(EntityType.MESSAGE),
                    chatId = chat.id,
                    clientId = characterMessage.id,
                    senderId = characterMessage.characterId,
                    isSentByUser = false,
                    text = "",
                    status = MessageStatus.STREAMING.value,
                    chatClientId = chat.clientId,
                ).also {
                    messageRepository.insertMessage(it)
                    messageFinisher.finishMessageAsync(it.id)
                    characterActivityLogRepository.logActivity(
                        activityType = ActivityType.MESSAGE_SENT,
                        characterId = characterMessage.characterId,
                        userId = userId
                    )
                }
            }

            call.respondSuccess(
                SendMessageResponse(
                    syncRequired = false,
                    userMessage = newUserMessage?.toMessageDto(mapper),
                    characterMessage = newCharacterMessage?.toMessageDto(mapper)
                )
            )
        }

        /**
         * POST /chats/{chatId}/messages/{messageId}/regenerate
         * Повторная генерация AI сообщения при ошибке
         */
        post("/{chatId}/messages/{messageId}/regenerate") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")
            val messageId = call.parameters["messageId"]
                ?: throw BadRequestException("Message ID is required")

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            val message = messageRepository.findByClientId(messageId)
                ?: throw BadRequestException("Message not found")


            if (message.isSentByUser) {
                throw BadRequestException("Can only regenerate AI messages")
            }

            val request = call.receive<ReloadMessageRequest>()

            val syncRequired = messageRepository.hasChangesAfter(
                chatId = chat.id,
                timestamp = UtcTimestamp.parse(request.lastChatSyncTimestamp)
            )

            if (syncRequired) {
                return@post call.respondSuccess(ReloadMessageResponse(syncRequired = true))
            }

            messageFinisher.finishMessageAsync(message.id)

            call.respondSuccess(ReloadMessageResponse(syncRequired = false))
        }

        /**
         * PUT /chats/{chatId}/messages/read
         * Отметка сообщений как прочитанных
         */
        put("/{chatId}/messages/read") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")
            val request = call.receive<MarkAsReadRequest>()

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            val messageDbos = messageRepository.getMessagesByClientIds(request.messageIds)

            val chatIds = messageDbos.map { it.chatId }.toSet().toList()

            if (!chatRepository.doAllChatsBelongToUser(chatIds, userId)) {
                throw ForbiddenException("Some of messages are not from your chats")
            }

            val updatedCount = messageRepository.markMessagesAsRead(
                messageIds = messageDbos.map { it.id }
            )

            call.respondSuccess(
                MarkAsReadResponse(
                    success = true,
                    updatedCount = updatedCount
                )
            )
        }

        /**
         * DELETE /chats/{chatId}/messages
         * Удаление сообщений
         */
        post("/messages/delete") {
            val userId = sessionRepository.verifyToken(call).userId
            val request = call.receive<DeleteMessagesRequest>()

            val messageIds = request.messageIds.distinct()
            val messageDbos = messageRepository.getMessagesByClientIds(messageIds)

            val chatIds = messageDbos.map { it.chatId }.toSet().toList()

            if (!chatRepository.doAllChatsBelongToUser(chatIds, userId)) {
                throw ForbiddenException("Some of messages are not from your chats")
            }

            messageRepository.deleteMessagesByIds(
                messageIds = messageDbos.map { it.id }
            )

            call.respondSuccess(DeleteMessagesResponse(isSuccess = true))
        }

        // ========== SSE СТРИМИНГ ==========

        /**
         * GET /chats/{chatId}/messages/{messageId}/stream
         * Подписка на стрим обновлений сообщения через SSE
         */
// ========== SSE СТРИМИНГ ==========

        /**
         * POST /chats/{chatId}/messages/{messageId}/stream
         * Подписка на стрим обновлений сообщения через SSE с поддержкой синхронизации
         */
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

            // Устанавливаем заголовки для SSE
            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.header(HttpHeaders.Connection, "keep-alive")
            call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            call.response.header("Cache-Control", "no-cache")
            call.response.header("X-Accel-Buffering", "no")

            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    // Отправляем текущее состояние сообщения сразу
                    val currentMessage = messageRepository.getMessageById(message.id)
                        ?: return@respondTextWriter

                    val initialChunk = StreamMessageChunk(
                        chunk = currentMessage.text,
                        isComplete = currentMessage.status == MessageStatus.COMPLETED.value,
                        isFailed = currentMessage.status == MessageStatus.FAILED.value,
                        nsfw = false
                    )

                    write("data: ${defaultJson.encodeToString(initialChunk)}\n\n")
                    flush()

                    // Если сообщение уже завершено, отправляем финальный чанк с синхронизацией и закрываем
                    if (currentMessage.textVersion == request.version &&
                        currentMessage.status == MessageStatus.COMPLETED.value
                    ) {
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

                        write("data: ${defaultJson.encodeToString(finalChunk)}\n\n")
                        flush()
                        return@respondTextWriter
                    }

                    if (currentMessage.textVersion != request.version ||
                        currentMessage.status == MessageStatus.FAILED.value
                    ) {
                        messageFinisher.finishMessageAsync(message.id)
                    }

                    // Подписываемся на обновления сообщения
                    messageRepository.streamMessageUpdates(message.id)
                        .collect { update ->
                            val chunk = if (update.isComplete || update.isFailed) {
                                // Финальный чанк с синхронизацией
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
                                // Обычный чанк без синхронизации
                                StreamMessageChunk(
                                    chunk = update.newText,
                                    isComplete = false,
                                    isFailed = false,
                                    nsfw = false
                                )
                            }

                            withContext(Dispatchers.IO) {
                                write("data: ${defaultJson.encodeToString(chunk)}\n\n")
                                flush()
                            }

                            // Если сообщение завершено или ошибка - закрываем стрим
                            if (update.isComplete || update.isFailed) {
                                return@collect
                            }
                        }
                } catch (e: Exception) {
                    // Отправляем ошибку и закрываем соединение
                    val errorChunk = StreamMessageChunk(
                        chunk = "",
                        isComplete = false,
                        isFailed = true,
                        nsfw = false
                    )

                    try {
                        write("data: ${defaultJson.encodeToString(errorChunk)}\n\n")
                        flush()
                    } catch (writeException: Exception) {
                        // Игнорируем ошибки записи при закрытии соединения
                    }

                    call.application.log.error("Error in SSE stream for message $messageId", e)
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
    // Логика синхронизации чата (аналогично batch sync)
    val syncTimestamp = chatSyncRequest.lastSyncTimestamp?.let {
        UtcTimestamp.parse(it)
    } ?: UtcTimestamp.now().subtractYears(100)

    val oldestLoaded = chatSyncRequest.oldestLoadedMessageTime?.let { UtcTimestamp.parse(it) }
    val newestLoaded = chatSyncRequest.newestLoadedMessageTime?.let { UtcTimestamp.parse(it) }

    // Новые сообщения
    val newMessages = if (newestLoaded != null) {
        messageRepository.getMessagesCreatedAfter(chat.id, newestLoaded)
    } else {
        messageRepository.getMessagesCreatedAfter(chat.id, syncTimestamp)
    }

    // Обновленные сообщения в загруженном диапазоне
    val updatedMessages = if (oldestLoaded != null && newestLoaded != null) {
        messageRepository.getMessagesUpdatedInRange(
            chatId = chat.id,
            afterTimestamp = syncTimestamp,
            oldestTime = oldestLoaded,
            newestTime = newestLoaded
        )
    } else {
        messageRepository.getMessagesUpdatedAfter(chat.id, syncTimestamp)
    }

    // Удаленные сообщения в загруженном диапазоне
    val deletedMessageIds = if (oldestLoaded != null && newestLoaded != null) {
        messageRepository.getDeletedMessageIdsInRange(
            chatId = chat.id,
            afterTimestamp = syncTimestamp,
            oldestTime = oldestLoaded,
            newestTime = newestLoaded
        )
    } else {
        messageRepository.getDeletedMessageIdsAfter(chat.id, syncTimestamp)
    }

    return ChatSyncResponse(
        chatId = chat.clientId,
        chat = chat.toChatDto(mapper),
        newMessages = newMessages.map { it.toMessageDto(mapper) },
        updatedMessages = updatedMessages.map { it.toMessageDto(mapper) },
        deletedMessageIds = deletedMessageIds,
        isDeleted = false
    )
}