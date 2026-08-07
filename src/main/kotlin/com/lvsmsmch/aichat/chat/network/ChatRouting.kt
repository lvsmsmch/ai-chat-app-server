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
 * Сами чаты: синхронизация, создание, чтение и удаление.
 *
 * Прежде здесь лежала тысяча строк — чаты, сообщения и генерация вперемешку.
 * Разложено по трём файлам: [configureChatRouting], [configureMessageRouting]
 * и [configureGenerationRouting]; параметры у всех одинаковые, потому что это
 * один и тот же путь /chats.
 */
fun Route.configureChatRouting(
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
        post("/sync") {
            val userId = sessionRepository.verifyToken(call).userId
            val request = call.receive<BatchSyncRequest>()

            val chatSyncResponses = mutableListOf<ChatSyncResponse>()
            val requestedChatIds = request.chatSyncRequests.map { it.chatId }.toSet()

            val allUserChats = chatRepository.getChatsByUserId(userId)

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

                    if (syncResponse.chatUpdatedResponse != null || syncResponse.messageListUpdatedResponse != null) {
                        chatSyncResponses.add(syncResponse)
                    }
                }
            }

            allUserChats
                .filter { it.clientId !in requestedChatIds }
                .forEach { chat ->
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

                    if (!chat.isDeleted) {
                        val limitedMessages = messageRepository.getMessagesPaginated(
                            chatId = chat.id,
                            cursor = null,
                            limit = 250
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
                    limitsResponse = userRepository.getLimits(userId)
                )
            )
        }


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

            // Дубликаты direct-чатов запрещены: быстрый двойной тап создавал два чата
            // с одним персонажем. Существующий чат просто возвращается клиенту.
            if (isDirect) {
                chatRepository.findChatByUserAndCharacter(userId, request.characterIds.first())?.let { existing ->
                    val existingSync = generateChatSyncResponse(
                        chat = existing,
                        chatSyncRequest = ChatSyncRequest(
                            chatId = existing.clientId,
                            lastChatSyncTimestamp = UtcTimestamp.year1900().toString(),
                            lastMessagesSyncTimestamp = UtcTimestamp.year1900().toString(),
                            oldestLoadedMessageTime = null,
                            newestLoadedMessageTime = null
                        ),
                        chatRepository = chatRepository,
                        messageRepository = messageRepository,
                        mapper = mapper
                    )
                    return@post call.respondSuccess(
                        CreateChatResponse(
                            isSuccess = true,
                            addInitMessageSuccess = false,
                            limitsResponse = userRepository.getLimits(userId),
                            chatSyncResponse = existingSync
                        )
                    )
                }
            }

            val isFirstChat = isDirect

            val chatDbo = ChatDbo(
                id = idGenerator.generateId(EntityType.CHAT),
                clientId = request.chatId,
                userId = userId,
                characterIds = request.characterIds,
                customName = request.customName,
                type = if (isDirect) ChatType.DIRECT else ChatType.GROUP,
                isFirstChatWithThisCharacter = isFirstChat
            )

            chatService.addChat(chatDbo)

            val shouldAddInitMessage = userRepository.getLimits(userId).limitUntil == null

            val initMessageAlreadyExists = request.initialMessageId != null &&
                    messageRepository.findByClientId(request.initialMessageId) != null

            if (request.initialMessageId != null && shouldAddInitMessage && !initMessageAlreadyExists) {
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
                    chatService.addMessage(it)
                    messageFinisher.finishMessageAsync(it.id)
                }
            }

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
                    limitsResponse = userRepository.getLimits(userId),
                    chatSyncResponse = chatSyncResponse
                )
            )
        }

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
            // Группа: в characters добавляем и УДАЛЁННЫХ участников, у которых
            // остались сообщения (клиенту нужны их имена/авы для истории);
            // актуальный состав — отдельным полем
            val enriched = if (chat.type == ChatType.GROUP) {
                val formerIds = messageRepository.getDistinctSenderIds(chat.id)
                    .filter { it.isNotBlank() } - chat.characterIds.toSet()
                val lang = mapper.languageOf(userId)
                val formerDtos = mapper.charactersDtoByIds(formerIds, lang)
                chatDto.copy(
                    characters = chatDto.characters + formerDtos,
                    activeCharacterIds = chat.characterIds,
                )
            } else chatDto
            call.respondSuccess(enriched)
        }

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

            // Смена состава группы: 2–25 существующих персонажей, только для
            // GROUP-чатов. Сообщения удалённых участников остаются в истории
            request.characterIds?.let { ids ->
                if (chat.type != ChatType.GROUP) {
                    throw BadRequestException("Only group chats can change characters")
                }
                val unique = ids.distinct()
                if (unique.size < 2 || unique.size > 25) {
                    throw BadRequestException("Group chat must have 2..25 characters")
                }
                unique.forEach { id ->
                    characterRepository.getCharacter(id)
                        ?: throw BadRequestException("Character not found: $id")
                }
            }

            request.cover?.let { validateChatCover(it) }

            chatRepository.updateChat(
                chatId = chat.id,
                customName = request.customName?.takeIf { it.isNotBlank() },
                characterIds = request.characterIds?.distinct(),
                cover = request.cover,
            )

            val updatedChat = chatRepository.getChatById(chat.id)!!

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

            val foundChatIds = chats.map { it.clientId }.toSet()
            val notFoundChatIds = chatClientIds.toSet() - foundChatIds
            if (notFoundChatIds.isNotEmpty()) {
                throw BadRequestException("Chats not found: ${notFoundChatIds.joinToString(", ")}")
            }

            val unauthorizedChats = chats.filter { it.userId != userId }
            if (unauthorizedChats.isNotEmpty()) {
                val unauthorizedIds = unauthorizedChats.map { it.clientId }
                throw ForbiddenException("Access denied to chats: ${unauthorizedIds.joinToString(", ")}")
            }

            chatRepository.deleteChatsByIds(chats.map { it.id })

            call.respondSuccess(
                DeleteChatsResponse(isSuccess = true)
            )
        }
    }
}


suspend fun generateChatSyncResponse(
    chat: ChatDbo,
    chatSyncRequest: ChatSyncRequest,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    mapper: Mapper
): ChatSyncResponse {

    val chatSyncTimestamp = chatSyncRequest.lastChatSyncTimestamp?.let {
        UtcTimestamp.parse(it)
    } ?: UtcTimestamp.year1900()

    val messagesSyncTimestamp = chatSyncRequest.lastMessagesSyncTimestamp?.let {
        UtcTimestamp.parse(it)
    } ?: UtcTimestamp.year1900()

    val oldestLoaded = chatSyncRequest.oldestLoadedMessageTime?.let { UtcTimestamp.parse(it) }
    val newestLoaded = chatSyncRequest.newestLoadedMessageTime?.let { UtcTimestamp.parse(it) }

    var chatUpdatedResponse: ChatUpdatedResponse? = null
    if (chat.lastModifiedAt > chatSyncTimestamp.toString() || chat.isDeleted) {
        chatUpdatedResponse = ChatUpdatedResponse(
            lastChatSyncTimestamp = UtcTimestamp.now().toString(),
            chat = chat.toChatDto(mapper),
            isChatDeleted = chat.isDeleted
        )
    }

    var messageListUpdatedResponse: MessageListUpdatedResponse? = null

    val newMessages = if (newestLoaded != null) {
        messageRepository.getMessagesCreatedAfter(chat.id, newestLoaded)
    } else {
        messageRepository.getMessagesCreatedAfter(chat.id, messagesSyncTimestamp)
    }

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
