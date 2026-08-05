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
 * Сообщения чата: история, отправка, жалобы, удаление, правка и очистка.
 */
fun Route.configureMessageRouting(
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

            // Идемпотентность: юзерское сообщение могло дойти при «неудачной» попытке
            // (клиент ретраит после обрыва сети) — не дублируем его, но characterMessage
            // ниже ОБЯЗАТЕЛЬНО обрабатываем, иначе ретрай оставляет клиента без ответа.
            val existingUserMessage = request.userMessage?.let { messageRepository.findByClientId(it.id) }

            val limits = userRepository.getLimits(userId)
            if (limits.limitUntil != null) {
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
                        limitsResponse = limits,
                        chatSyncResponse = chatSyncResponse
                    )
                )

                return@post
            }

            if (existingUserMessage == null) {
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
                        chatService.addMessage(it)
                    }
                }
            }

            request.characterMessage?.let { characterMessage ->
                val characterId = characterMessage.characterId
                val existingCharMessage = messageRepository.findByClientId(characterMessage.id)
                when {
                    existingCharMessage == null -> MessageDbo(
                        id = idGenerator.generateId(EntityType.MESSAGE),
                        chatId = chat.id,
                        clientId = characterMessage.id,
                        senderId = characterId,
                        isSentByUser = false,
                        text = "",
                        status = MessageStatus.STREAMING.value,
                        chatClientId = chat.clientId,
                    ).also {
                        chatService.addMessage(it)
                        messageFinisher.finishMessageAsync(it.id)
                    }
                    // Ретрай: плейсхолдер уже есть, но генерация не идёт — перезапускаем
                    existingCharMessage.status != MessageStatus.COMPLETED.value &&
                        !messageFinisher.isFinishing(existingCharMessage.id) ->
                        messageFinisher.finishMessageAsync(existingCharMessage.id)
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
                    limitsResponse = userRepository.getLimits(userId),
                    chatSyncResponse = chatSyncResponse
                )
            )
        }

        /**
         * Перегенерировать ответ персонажа. Новый вариант дописывается к
         * сообщению и становится выбранным; предыдущие остаются, между ними
         * можно переключаться.
         *
         * Лимит [MAX_VARIANTS] считается по числу уже сохранённых вариантов,
         * а не отдельным счётчиком: счётчик пришлось бы держать в согласии со
         * списком, и однажды они разошлись бы.
         */
        post("/{chatId}/messages/{messageId}/retry") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatClientId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")
            val messageClientId = call.parameters["messageId"]
                ?: throw BadRequestException("Message ID is required")

            val chat = chatRepository.getChatByClientId(chatClientId)
                ?: throw ChatNotFoundException(chatClientId)
            if (chat.userId != userId) throw ForbiddenException("Access denied to this chat")

            val message = messageRepository.findByClientId(messageClientId)
                ?: throw BadRequestException("Message not found")
            if (message.chatId != chat.id) {
                throw BadRequestException("Message does not belong to this chat")
            }
            if (message.isSentByUser) {
                throw BadRequestException("Only character messages can be retried")
            }

            val stored = message.variants.ifEmpty { listOf(message.text) }
            if (stored.size >= MAX_VARIANTS) {
                throw ForbiddenException(errorMessage = "retry_limit_reached")
            }

            val limits = userRepository.getLimits(userId)
            if (limits.limitUntil != null) {
                return@post call.respondSuccess(
                    SendMessageResponse(
                        isSuccess = false,
                        limitsResponse = limits,
                        chatSyncResponse = generateChatSyncResponse(
                            chat = chat,
                            chatSyncRequest = ChatSyncRequest(chat.clientId),
                            chatRepository = chatRepository,
                            messageRepository = messageRepository,
                            mapper = mapper,
                        ),
                    )
                )
            }

            // Исходный вариант фиксируем ДО генерации: иначе стриминг затрёт
            // текст, и вернуться к первому ответу будет некуда
            messageRepository.ensureVariantsInitialized(message.id)
            messageRepository.updateMessage(
                messageId = message.id,
                text = "",
                status = MessageStatus.STREAMING.value,
            )
            messageFinisher.finishMessageAsync(message.id)

            call.respondSuccess(
                SendMessageResponse(
                    isSuccess = true,
                    limitsResponse = userRepository.getLimits(userId),
                    chatSyncResponse = generateChatSyncResponse(
                        chat = chat,
                        chatSyncRequest = ChatSyncRequest(chat.clientId),
                        chatRepository = chatRepository,
                        messageRepository = messageRepository,
                        mapper = mapper,
                    ),
                )
            )
        }

        /**
         * Переключить активный вариант ответа. Меняется и `text` сообщения —
         * поэтому следующая генерация увидит в истории именно тот ответ,
         * который юзер оставил на экране.
         */
        post("/{chatId}/messages/{messageId}/variant") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatClientId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")
            val messageClientId = call.parameters["messageId"]
                ?: throw BadRequestException("Message ID is required")
            val request = call.receive<SelectVariantRequest>()

            val chat = chatRepository.getChatByClientId(chatClientId)
                ?: throw ChatNotFoundException(chatClientId)
            if (chat.userId != userId) throw ForbiddenException("Access denied to this chat")

            val message = messageRepository.findByClientId(messageClientId)
                ?: throw BadRequestException("Message not found")
            if (message.chatId != chat.id) {
                throw BadRequestException("Message does not belong to this chat")
            }

            if (!messageRepository.selectVariant(message.id, request.index)) {
                throw BadRequestException("Unknown variant index")
            }
            val updated = messageRepository.findByClientId(messageClientId)
            call.respondSuccess(
                SelectVariantResponse(
                    isSuccess = true,
                    text = updated?.text.orEmpty(),
                    variantsCount = updated?.variants?.size ?: 0,
                    selectedVariant = updated?.selectedVariant ?: request.index,
                )
            )
        }

        post("/messages/{messageId}/report") {
            val currentUserId = sessionRepository.verifyToken(call).userId
            val messageClientId = call.parameters["messageId"]
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

        post("/messages/delete") {
            val userId = sessionRepository.verifyToken(call).userId
            val request = call.receive<DeleteMessagesRequest>()

            val messageClientIds = request.messageIds.distinct()
            val messageDbos = messageRepository.getMessagesByClientIds(messageClientIds)

            // Неизвестные id игнорируем: клиент может удалять «фантомы» — сообщения,
            // которые так и не дошли до сервера. Раньше это давало 400 и блокировало
            // удаление ВСЕГО выбранного (даже локально).
            if (messageDbos.isEmpty()) {
                return@post call.respondSuccess(
                    DeleteMessagesResponse(
                        isSuccess = true,
                        chatSyncResponse = generateChatSyncResponse(
                            chat = chatRepository.getChatByClientId(request.chatId)
                                ?: throw ChatNotFoundException(request.chatId),
                            chatSyncRequest = request.chatSyncRequest,
                            chatRepository = chatRepository,
                            messageRepository = messageRepository,
                            mapper = mapper
                        )
                    )
                )
            }

            val chatIds = messageDbos.map { it.chatId }.toSet()
            if (chatIds.size != 1) {
                throw BadRequestException("All messages must belong to the same chat")
            }

            val chatId = chatIds.first()
            val chat = chatRepository.getChatById(chatId)
                ?: throw BadRequestException("Chat not found")

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            messageRepository.deleteMessagesByIds(
                chatId = chatId,
                messageIds = messageDbos.map { it.id }
            )

            val chatSyncResponse = generateChatSyncResponse(
                chat = chat,
                chatSyncRequest = request.chatSyncRequest,
                chatRepository = chatRepository,
                messageRepository = messageRepository,
                mapper = mapper
            )

            call.respondSuccess(
                DeleteMessagesResponse(
                    isSuccess = true,
                    chatSyncResponse = chatSyncResponse
                )
            )
        }



        /**
         * Редактирование СВОЕГО сообщения: текст заменяется, вся история после него
         * удаляется, и (в директе) персонаж отвечает заново — как будто разговор
         * продолжился с этого места.
         */
        post("/{chatId}/messages/{messageId}/edit") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatClientId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")
            val messageClientId = call.parameters["messageId"]
                ?: throw BadRequestException("Message ID is required")
            val request = call.receive<EditMessageRequest>()

            val chat = chatRepository.getChatByClientId(chatClientId)
                ?: throw ChatNotFoundException(chatClientId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            val message = messageRepository.findByClientId(messageClientId)
                ?: throw BadRequestException("Message not found")

            if (message.chatId != chat.id) {
                throw BadRequestException("Message does not belong to this chat")
            }

            if (!message.isSentByUser) {
                throw BadRequestException("Only own messages can be edited")
            }

            validateMessageText(request.newText)

            messageRepository.deleteMessagesCreatedAfter(chat.id, message.createdAt)
            messageRepository.updateMessage(
                messageId = message.id,
                text = request.newText,
                status = MessageStatus.COMPLETED.value,
            )

            request.characterMessageId?.let { charMsgId ->
                if (messageRepository.findByClientId(charMsgId) == null) {
                    val characterId = chat.characterIds.first()
                    MessageDbo(
                        id = idGenerator.generateId(EntityType.MESSAGE),
                        chatId = chat.id,
                        chatClientId = chat.clientId,
                        clientId = charMsgId,
                        senderId = characterId,
                        isSentByUser = false,
                        text = "",
                        status = MessageStatus.STREAMING.value,
                    ).also {
                        chatService.addMessage(it)
                        messageFinisher.finishMessageAsync(it.id)
                    }
                }
            }

            call.respondSuccess(IsSuccessResponse(isSuccess = true))
        }

        /**
         * Очистка истории чата (все сообщения разом). Если передан initialMessageId —
         * это Restart chat: после очистки персонаж заново присылает приветствие
         * по той же логике, что и при создании чата.
         */
        post("/{chatId}/clear") {
            val userId = sessionRepository.verifyToken(call).userId
            val chatClientId = call.parameters["chatId"]
                ?: throw BadRequestException("Chat ID is required")
            val request = call.receive<ClearChatRequest>()

            val chat = chatRepository.getChatByClientId(chatClientId)
                ?: throw ChatNotFoundException(chatClientId)

            if (chat.userId != userId) {
                throw ForbiddenException("Access denied to this chat")
            }

            messageRepository.deleteAllMessagesInChat(chat.id)

            request.initialMessageId?.let { greetingId ->
                if (messageRepository.findByClientId(greetingId) == null) {
                    val characterId = chat.characterIds.first()
                    MessageDbo(
                        id = idGenerator.generateId(EntityType.MESSAGE),
                        chatId = chat.id,
                        chatClientId = chat.clientId,
                        clientId = greetingId,
                        senderId = characterId,
                        isSentByUser = false,
                        text = "",
                        status = MessageStatus.STREAMING.value,
                    ).also {
                        chatService.addMessage(it)
                        messageFinisher.finishMessageAsync(it.id)
                    }
                }
            }

            call.respondSuccess(IsSuccessResponse(isSuccess = true))
        }

        /**
         * Генерация изображения персонажем: создаётся message-плейсхолдер
         * (isImage=true), результат клиент забирает обычным /stream.
         * Идемпотентно: повторный вызов с тем же messageId перезапускает
         * упавшую генерацию (ретрай после сетевой ошибки).
         */
    }
}

/** Исходный ответ + три ретрая. */
private const val MAX_VARIANTS = 4
