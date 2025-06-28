// ============= COMPLETE CHAT REST API ROUTING =============

package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.MessageFinisher
import com.lvsmsmch.aichat.chat.database.*
import com.lvsmsmch.aichat.utils.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString

fun Routing.configureChatRouting(
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    characterRepository: CharacterRepository,
    sessionRepository: SessionRepository,
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

                if (chat != null && chat.userId == userId && !chat.isDeleted) {

                    if (chatSyncRequest.isDeleted == true) {
                        chatRepository.deleteChat(chatSyncRequest.chatId)
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
                            lastSyncTimestamp = UtcTimestamp.now().toString()
                        )
                    )
                }
            }


            allUserChats
                .filter { !it.isDeleted && it.clientId !in requestedChatIds }
                .forEach { chat ->
                    // Для новых чатов ограничиваем количество сообщений
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
                            updatedMessages = emptyList(),
                            deletedMessageIds = emptyList(),
                            lastSyncTimestamp = UtcTimestamp.now().toString()
                        )
                    )
                }

            // Находим удаленные чаты
            val deletedChatIds = allUserChats
                .filter { it.isDeleted && it.clientId in requestedChatIds }
                .map { it.clientId }

            call.respondSuccess(
                BatchSyncResponse(
                    chatSyncResponses = chatSyncResponses,
                    deletedChatIds = deletedChatIds
                )
            )
        }

        // ========== УПРАВЛЕНИЕ ЧАТАМИ ==========

        /**
         * POST /chats
         * Создание нового чата (поддержка direct и group чатов)
         */
        post("/new/direct") {
            val userId = sessionRepository.verifyToken(call).userId
            val request = call.receive<CreateDirectChatRequest>()

            val characterDbo = characterRepository.getCharacter(request.characterId)
                ?: throw ValidationException("Character not found")

            // Проверяем существующий чат
            val existingChat = chatRepository.findChatByUserAndCharacter(userId, request.characterId)

            if (existingChat != null && !existingChat.isDeleted) {
                throw ValidationException("Chat with this character already exists")
            }

            // Создаем чат
            val chatDbo = ChatDbo(
                id = idGenerator.generateId(EntityType.CHAT),
                clientId = request.chatId,
                userId = userId,
                characterIds = listOf(request.characterId),
                chatType = ChatType.DIRECT
            )

            chatRepository.insertChat(chatDbo)

            val newCharacterMessage = MessageDbo(
                id = idGenerator.generateId(EntityType.MESSAGE),
                chatId = chatDbo.id,
                chatClientId = chatDbo.clientId,
                clientId = request.initialMessageId,
                senderId = request.characterId,
                isSentByUser = false,
                text = "",
                status = MessageStatus.STREAMING.value,
            ).also {
                messageRepository.insertMessage(it)
                messageFinisher.finishMessageAsync(it.id)
            }

            val chatDto = chatDbo.toChatDto(mapper)

            call.respondSuccess(
                CreateChatResponse(
                    chat = chatDto,
                    initialMessage = newCharacterMessage.toMessageDto(mapper)
                )
            )
        }

        post("/new/group") {
            val userId = sessionRepository.verifyToken(call).userId
            val request = call.receive<CreateGroupChatRequest>()

            if (request.characterIds.size !in 2..10) {
                throw ValidationException("Unsupported size of characters (should be from 2 to 10)")
            }

            val characters = request.characterIds.mapNotNull { charId ->
                characterRepository.getCharacter(charId)
            }

            if (characters.size != request.characterIds.size) {
                throw ValidationException("Some characters not found")
            }

            val chatDbo = ChatDbo(
                id = idGenerator.generateId(EntityType.CHAT),
                userId = userId,
                characterIds = request.characterIds,
                chatType = ChatType.GROUP
            )

            chatRepository.insertChat(chatDbo)

            val chatDto = chatDbo.toChatDto(mapper)

            call.respondSuccess(CreateChatResponse(chat = chatDto))
        }

        /**
         * GET /chats/{chatId}
         * Получение деталей конкретного чата
         */
        get("/{chatId}") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw ValidationException("Chat ID is required")

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
                ?: throw ValidationException("Chat ID is required")
            val request = call.receive<UpdateChatRequest>()

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            chatRepository.updateChat(
                chatId = chat.id,
                isChatMuted = request.isMuted
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
                ?: throw ValidationException("Chat ID is required")

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            chatRepository.deleteChat(chat.id)
            call.respondSuccess()
        }

        // ========== СООБЩЕНИЯ ==========

        /**
         * GET /chats/{chatId}/messages
         * Получение сообщений с курсорной пагинацией
         */
        get("/{chatId}/messages") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw ValidationException("Chat ID is required")

            // Парсим query параметры в request объект
            val request = call.receive<GetMessagesRequest>()

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            if (request.limit !in 1..100) {
                throw ValidationException("Limit must be between 1 and 100")
            }

            val messages = messageRepository.getMessagesPaginated(
                chatId = chat.id,
                cursor = request.cursor,
                limit = request.limit + 1
            )

            val hasMore = messages.size > request.limit
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
                ?: throw ValidationException("Chat ID is required")
            val request = call.receive<SendMessageRequest>()

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            // Проверяем умную синхронизацию
            val syncRequired = if (request.lastChatSyncTimestamp != null) {
                val lastSync = UtcTimestamp.parse(request.lastChatSyncTimestamp)
                messageRepository.hasChangesAfter(chat.id, lastSync)
            } else false

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
                ?: throw ValidationException("Chat ID is required")
            val messageId = call.parameters["messageId"]
                ?: throw ValidationException("Message ID is required")

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            val message = messageRepository.findByClientId(messageId)
                ?: throw ValidationException("Message not found")

            if (message.isSentByUser) {
                throw ValidationException("Can only regenerate AI messages")
            }

            messageFinisher.finishMessageAsync(message.id)

            call.respondSuccess()
        }

        /**
         * PUT /chats/{chatId}/messages/read
         * Отметка сообщений как прочитанных
         */
        put("/{chatId}/messages/read") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw ValidationException("Chat ID is required")
            val request = call.receive<MarkAsReadRequest>()

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            val updatedCount = messageRepository.markMessagesAsRead(
                messageIds = request.messageIds,
                userId = userId
            )

            call.respondSuccess(
                MarkAsReadResponse(
                    success = true,
                    updatedCount = updatedCount
                )
            )
        }

        // ========== SSE СТРИМИНГ ==========

        /**
         * GET /chats/{chatId}/messages/{messageId}/stream
         * Подписка на стрим обновлений сообщения через SSE
         */
        get("/{chatId}/messages/{messageId}/stream") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatId = call.parameters["chatId"]
                ?: throw ValidationException("Chat ID is required")
            val messageId = call.parameters["messageId"]
                ?: throw ValidationException("Message ID is required")

            val chat = chatRepository.getChatByClientId(chatId)
                ?: throw ChatNotFoundException(chatId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            val message = messageRepository.findByClientId(messageId)
                ?: throw ValidationException("Message not found")

            if (message.chatId != chat.id) {
                throw ValidationException("Message does not belong to this chat")
            }

            if (message.isSentByUser) {
                throw ValidationException("Cannot stream user messages")
            }

            // Устанавливаем заголовки для SSE
            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.header(HttpHeaders.Connection, "keep-alive")
            call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())

            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    // Отправляем текущее состояние сообщения сразу
                    val currentMessage = messageRepository.getMessageById(message.id)
                    if (currentMessage != null) {
                        val initialChunk = MessageStreamChunk(
                            chunk = currentMessage.text,
                            isComplete = currentMessage.status == MessageStatus.COMPLETED.value,
                            status = when (currentMessage.status) {
                                MessageStatus.COMPLETED.value -> MessageStatus.COMPLETED.value
                                MessageStatus.FAILED.value -> MessageStatus.FAILED.value
                                else -> MessageStatus.STREAMING.value
                            }
                        )

                        @Suppress("BlockingMethodInNonBlockingContext")
                        write("data: ${defaultJson.encodeToString(initialChunk)}\n\n")
                        @Suppress("BlockingMethodInNonBlockingContext")
                        flush()
                    }

                    // Если сообщение уже завершено, закрываем стрим
                    if (currentMessage?.status == MessageStatus.COMPLETED.value ||
                        currentMessage?.status == MessageStatus.FAILED.value
                    ) {
                        return@respondTextWriter
                    }

                    // Подписываемся на обновления сообщения
                    messageRepository.streamMessageUpdates(message.id)
                        .takeWhile { !it.isComplete && !it.isFailed }
                        .collect { update ->
                            val chunk = MessageStreamChunk(
                                chunk = update.newText,
                                isComplete = update.isComplete,
                                status = when {
                                    update.isFailed -> MessageStatus.FAILED.value
                                    update.isComplete -> MessageStatus.COMPLETED.value
                                    else -> MessageStatus.STREAMING.value
                                },
                                error = if (update.isFailed) "Generation failed" else null
                            )

                            @Suppress("BlockingMethodInNonBlockingContext")
                            write("data: ${defaultJson.encodeToString(chunk)}\n\n")
                            @Suppress("BlockingMethodInNonBlockingContext")
                            flush()

                            // Если сообщение завершено или ошибка - закрываем стрим
                            if (update.isComplete || update.isFailed) {
                                return@collect
                            }
                        }
                } catch (e: Exception) {
                    // Отправляем ошибку и закрываем соединение
                    val errorChunk = MessageStreamChunk(
                        chunk = "",
                        isComplete = true,
                        status = MessageStatus.FAILED.value,
                        error = "Streaming error: ${e.message}"
                    )

                    try {
                        @Suppress("BlockingMethodInNonBlockingContext")
                        write("data: ${defaultJson.encodeToString(errorChunk)}\n\n")
                        @Suppress("BlockingMethodInNonBlockingContext")
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