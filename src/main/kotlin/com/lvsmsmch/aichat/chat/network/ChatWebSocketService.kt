package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.character.database.ActivityType
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.database.*
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ChatWebSocketService(
    val application: Application,
    val userId: String,
    val chatRepository: ChatRepository,
    val messageRepository: MessageRepository,
    val characterRepository: CharacterRepository,
    val characterActivityLogRepository: CharacterActivityLogRepository,
    val idGenerator: IdGenerator,
    val mapper: Mapper,
    val onShutdown: () -> Unit
) {

    companion object {
        private const val SESSION_TIMEOUT_MS = 300_000L // 5 minutes
        private const val PING_INTERVAL_MS = 30_000L // 30 seconds
        private const val MAX_CONNECTIONS_PER_USER = 10
        private const val REQUEST_TIMEOUT_MS = 30_000L // 30 seconds
        private const val MONITOR_RETRY_DELAY_MS = 5_000L // 5 seconds
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    data class SessionInfo(
        val id: String = UUID.randomUUID().toString(),
        val session: WebSocketSession,
        var lastActivity: Long = System.currentTimeMillis(),
    )

    private val sessions: MutableList<SessionInfo> = Collections.synchronizedList(mutableListOf())
    private val consumingJobs: MutableMap<String, Job> = ConcurrentHashMap()
    private val pingPongJob: Job = createPingPongJob()
    private val chatChangesMonitorJob: Job = createChatChangesMonitorJob()
    private val messageChangesMonitorJob = ConcurrentHashMap<String, Job>()
    private val messageCompletionJob: Job = createMessageCompletionJob()
    private val messagesToBeCompleted = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                completeUncompletedMessages()
            } catch (e: Exception) {
                application.log.error("Error completing uncompleted messages for user $userId", e)
            }
        }
    }

    suspend fun addConnection(session: WebSocketSession) {
        if (sessions.size >= MAX_CONNECTIONS_PER_USER) {
            try {
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Too many connections"))
            } catch (e: Exception) {
                application.log.debug("Error closing excess connection: ${e.message}")
            }
            return
        }

        val sessionInfo = SessionInfo(session = session)
        sessionInfo.lastActivity = System.currentTimeMillis()
        sessions.add(sessionInfo)

        consumingJobs[sessionInfo.id] = scope.launch {
            try {
                session.incoming.consumeEach { frame ->
                    try {
                        sessions.find { it.session == session }?.lastActivity = System.currentTimeMillis()

                        val request = try {
                            defaultJson.decodeFromString<ChatWsRequest>((frame as Frame.Text).readText())
                        } catch (e: SerializationException) {
                            sendEvent(session, ChatWsEvent.Error(message = "Invalid JSON format"))
                            return@consumeEach
                        } catch (e: ClassCastException) {
                            sendEvent(session, ChatWsEvent.Error(message = "Invalid frame type"))
                            return@consumeEach
                        } catch (e: Exception) {
                            sendEvent(session, ChatWsEvent.Error(message = "Invalid request format"))
                            return@consumeEach
                        }

                        processWebSocketRequest(request = request, session = session)

                    } catch (e: Exception) {
                        application.log.error("Error processing frame for user $userId", e)
                        sendEvent(session, ChatWsEvent.Error(message = "Error processing request"))
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                application.log.debug("WebSocket receive channel closed for user $userId")
                removeConnection(session)
            } catch (e: Exception) {
                application.log.error("Error in consuming job for user $userId", e)
                removeConnection(session)
            }
        }
    }

    fun removeConnection(session: WebSocketSession) {
        synchronized(this) {
            val sessionInfo = sessions.find { it.session == session } ?: return

            try {
                consumingJobs[sessionInfo.id]?.cancel()
                consumingJobs.remove(sessionInfo.id)
                sessions.removeIf { it.id == sessionInfo.id }

                if (sessions.isEmpty()) {
                    application.log.debug("Last session removed for user $userId, shutting down service")

                    try {
                        pingPongJob.cancel()
                    } catch (e: Exception) {
                        application.log.debug("Error cancelling ping job: ${e.message}")
                    }
                    try {
                        chatChangesMonitorJob.cancel()
                    } catch (e: Exception) {
                        application.log.debug("Error cancelling chat monitor job: ${e.message}")
                    }
                    try {
                        messageCompletionJob.cancel()
                    } catch (e: Exception) {
                        application.log.debug("Error cancelling message completion job: ${e.message}")
                    }

                    messageChangesMonitorJob.values.forEach { job ->
                        try {
                            job.cancel()
                        } catch (e: Exception) {
                            application.log.debug("Error cancelling message monitor job: ${e.message}")
                        }
                    }

                    try {
                        messagesToBeCompleted.close()
                    } catch (e: Exception) {
                        application.log.debug("Error closing messages channel: ${e.message}")
                    }
                    try {
                        scope.cancel()
                    } catch (e: Exception) {
                        application.log.debug("Error cancelling scope: ${e.message}")
                    }

                    try {
                        onShutdown()
                    } catch (e: Exception) {
                        application.log.error("Error in shutdown callback for user $userId", e)
                    }
                }
            } catch (e: Exception) {
                application.log.error("Error removing connection for user $userId", e)
            }
        }
    }

    private suspend fun processWebSocketRequest(request: ChatWsRequest, session: WebSocketSession) {
        try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                when (request) {
                    is ChatWsRequest.SyncChatListRequest -> processSyncChatListRequest(request, session)
                    is ChatWsRequest.AddChatRequest -> processAddChatRequest(request, session)
                    is ChatWsRequest.UpdateChatRequest -> processUpdateChatRequest(request, session)
                    is ChatWsRequest.DeleteChatRequest -> processDeleteChatRequest(request, session)
                }
            }
        } catch (e: TimeoutCancellationException) {
            application.log.warn("Request timeout for user $userId")
            sendEvent(session, ChatWsEvent.Error(message = "Request timed out"))
        } catch (e: Exception) {
            application.log.error("Error processing WebSocket request for user $userId", e)
            sendEvent(session, ChatWsEvent.Error(message = "Internal server error"))
        }
    }

    private suspend fun processSyncChatListRequest(
        request: ChatWsRequest.SyncChatListRequest,
        session: WebSocketSession
    ) {
        try {
            val lastSyncTimestamp = request.lastSyncTimestamp ?: UtcTimestamp.now().subtractYears(100)

            val addedChats: MutableList<ChatDbo> = mutableListOf()
            val potentiallyUpdatedChats: MutableList<ChatDbo> = mutableListOf()
            val deletedChatClientIds: MutableList<String> = mutableListOf()

            chatRepository.getChatsByUserId(userId).forEach { chatDbo ->
                when {
                    !chatDbo.isDeleted && chatDbo.createdAt > lastSyncTimestamp -> {
                        addedChats.add(chatDbo)
                    }

                    chatDbo.isDeleted && chatDbo.deletedAt > lastSyncTimestamp -> {
                        deletedChatClientIds.add(chatDbo.clientId)
                    }

                    !chatDbo.isDeleted -> {
                        potentiallyUpdatedChats.add(chatDbo)
                    }
                }
            }

            deletedChatClientIds.forEach {
                sendEvent(session, ChatWsEvent.ChatDeleted(serverTimestamp = UtcTimestamp.now(), chatId = it))
            }

            addedChats.map { addedChat ->
                ChatWsEvent.ChatAdded(
                    serverTimestamp = UtcTimestamp.now(),
                    chatId = addedChat.clientId,
                    addedChatDto = addedChat.toAddedChatDto(mapper),
                    addedMessages = messageRepository.getAllMessagesByChatId(addedChat.id)
                        .filter { !it.isDeleted }
                        .map { it.toAddedMessageDto(mapper) }
                )
            }.forEach { sendEvent(session, it) }

            potentiallyUpdatedChats.map { chatDbo ->
                val messageDbos = messageRepository.getAllMessagesByChatId(chatId = chatDbo.id)
                val addedMessages: MutableList<MessageDbo> = mutableListOf()
                val updatedMessages: MutableList<MessageDbo> = mutableListOf()
                val deletedMessageIds: MutableList<String> = mutableListOf()
                messageDbos.forEach { messageDbo ->
                    when {
                        !messageDbo.isDeleted && messageDbo.createdAt > lastSyncTimestamp -> {
                            addedMessages.add(messageDbo)
                        }

                        messageDbo.isDeleted && messageDbo.deletedAt > lastSyncTimestamp -> {
                            deletedMessageIds.add(messageDbo.id)
                        }

                        !messageDbo.isDeleted && messageDbo.lastModifiedAt > lastSyncTimestamp -> {
                            updatedMessages.add(messageDbo)
                        }
                    }
                }
                ChatWsEvent.ChatUpdated(
                    serverTimestamp = UtcTimestamp.now(),
                    chatId = chatDbo.clientId,
                    updatedChatDto = chatDbo
                        .takeIf { it.lastModifiedAt > lastSyncTimestamp }
                        ?.toUpdatedChatDto(mapper),
                    addedMessages = addedMessages.map { it.toAddedMessageDto(mapper) }.takeIf { it.isNotEmpty() },
                    updatedMessages = updatedMessages.map { it.toUpdatedMessageDto(mapper) }.takeIf { it.isNotEmpty() },
                    deletedMessagesIds = deletedMessageIds.takeIf { it.isNotEmpty() }
                )
            }.filter {
                it.updatedChatDto != null || it.addedMessages != null || it.updatedMessages != null ||
                        it.deletedMessagesIds != null
            }.forEach { sendEvent(session, it) }

//            sendEvent(session, ChatWsEvent.SyncChatListFinished(UtcTimestamp.now()))
        } catch (e: Exception) {
            application.log.error("Error syncing chat list for user $userId", e)
            sendEvent(session, ChatWsEvent.Error(message = "Error syncing chat list"))
        }
    }

    private suspend fun processAddChatRequest(request: ChatWsRequest.AddChatRequest, session: WebSocketSession) {
        try {
            val addChatDto = request.addChatDto
            if (addChatDto.characterIds.isEmpty()) {
                return sendEvent(session, ChatWsEvent.Error(message = "Character ids is empty."))
            }
            if (addChatDto.chatType != ChatType.DIRECT.code || addChatDto.characterIds.size != 1) {
                return sendEvent(session, ChatWsEvent.Error(message = "Group chats are not supported yet."))
            }

            val characterId = addChatDto.characterIds.first()
            val existingChat = chatRepository.findChatByUserAndCharacter(userId, characterId)
            if (existingChat != null) {
                return sendEvent(
                    session, ChatWsEvent.ChatAddFailed(
                        serverTimestamp = UtcTimestamp.now(),
                        chatId = request.chatId
                    )
                )
            }

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: return sendEvent(session, ChatWsEvent.Error(message = "Character doesn't exist."))

            val hasUnknownSenderId = request.addMessages
                ?.any { it.senderId != userId && !addChatDto.characterIds.contains(it.senderId) } ?: false

            if (hasUnknownSenderId) {
                return sendEvent(session, ChatWsEvent.Error(message = "Found unknown senderId"))
            }

            val chatDbo = ChatDbo(
                id = idGenerator.generateId(EntityType.CHAT),
                userId = userId,
                clientId = request.chatId,
                chatType = ChatType.entries.first { it.code == addChatDto.chatType },
                characterIds = listOf(characterId),
                isChatMuted = addChatDto.isChatMuted
            ).also { chatRepository.insertChat(it) }

            characterActivityLogRepository.logActivity(
                activityType = ActivityType.CHAT_CREATED,
                characterId = chatDbo.characterIds.first(),
                userId = userId
            )

            request.addMessages?.forEach { addMessageDto ->
                messageRepository.insertMessage(
                    MessageDbo(
                        id = idGenerator.generateId(EntityType.MESSAGE),
                        chatId = chatDbo.id,
                        clientId = addMessageDto.messageId,
                        senderId = addMessageDto.senderId,
                        isSentByUser = addMessageDto.senderId == userId,
                        text = addMessageDto.text,
                        completedStatus = addMessageDto.completedStatus,
                    )
                )

                if (addMessageDto.senderId == userId) {
                    characterActivityLogRepository.logActivity(
                        activityType = ActivityType.MESSAGE_SENT,
                        characterId = chatDbo.characterIds.first(),
                        userId = userId
                    )
                }
            }
        } catch (e: Exception) {
            application.log.error("Error adding chat for user $userId", e)
            sendEvent(session, ChatWsEvent.Error(message = "Error creating chat"))
        }
    }

    private suspend fun processUpdateChatRequest(request: ChatWsRequest.UpdateChatRequest, session: WebSocketSession) {
        try {
            val chatClientId = request.chatId

            val chatDbo = chatRepository.getChatsByUserId(userId).find { it.clientId == chatClientId }
                ?: return sendEvent(session, ChatWsEvent.Error(message = "Chat with client ID $chatClientId not found"))

            val chatId = chatDbo.id

            if (chatDbo.userId != userId) {
                return sendEvent(session, ChatWsEvent.Error(message = "Chat with client ID $chatClientId not found"))
            }
            if (chatDbo.isDeleted) {
                return sendEvent(session, ChatWsEvent.Error(message = "Chat is deleted"))
            }

            val knownSenderIds = listOf(listOf(userId), chatDbo.characterIds).flatten()
            val hasUnknownSenderId = request.addMessages?.any { !knownSenderIds.contains(it.senderId) } ?: false
            if (hasUnknownSenderId) {
                return sendEvent(session, ChatWsEvent.Error(message = "Found unknown senderId in addedMessages"))
            }

            val knownMessageClientIds = messageRepository.getAllMessagesByChatId(chatDbo.id).map { it.clientId }
            val hasUnknownIdToUpdate = request.updateMessages
                ?.any { !knownMessageClientIds.contains(it.messageId) } ?: false
            if (hasUnknownIdToUpdate) {
                return sendEvent(
                    session,
                    ChatWsEvent.Error(message = "Found unknown message id in updatedMessages")
                )
            }

            val hasUnknownIdToDelete = request.deleteMessageIds?.any { !knownMessageClientIds.contains(it) } ?: false
            if (hasUnknownIdToDelete) {
                return sendEvent(
                    session,
                    ChatWsEvent.Error(message = "Found unknown message id in deletedMessages")
                )
            }

            val knownCompletedStatuses = CompletedStatus.entries.map { it.code }
            val userCompletedStatuses = (request.addMessages ?: emptyList()).map { it.completedStatus } +
                    (request.updateMessages ?: emptyList()).mapNotNull { it.completedStatus }
            val hasUnknownCompletedStatus = userCompletedStatuses.any { !knownCompletedStatuses.contains(it) }
            if (hasUnknownCompletedStatus) {
                return sendEvent(session, ChatWsEvent.Error(message = "Found unknown completed status"))
            }

            request.updateChatDto?.let { updateChatDto ->
                chatRepository.updateChat(
                    chatId = chatId,
                    isChatMuted = updateChatDto.isChatMuted
                )
            }

            request.addMessages?.forEach { addMessageDto ->
                messageRepository.insertMessage(
                    MessageDbo(
                        id = idGenerator.generateId(EntityType.MESSAGE),
                        chatId = chatId,
                        clientId = addMessageDto.messageId,
                        senderId = addMessageDto.senderId,
                        isSentByUser = addMessageDto.senderId == userId,
                        text = addMessageDto.text,
                        completedStatus = addMessageDto.completedStatus
                    )
                )

                if (addMessageDto.senderId == userId) {
                    characterActivityLogRepository.logActivity(
                        activityType = ActivityType.MESSAGE_SENT,
                        characterId = chatDbo.characterIds.first(),
                        userId = userId
                    )
                }
            }

            request.updateMessages?.forEach { updateMessageDto ->
                val messages = messageRepository.getAllMessagesByChatId(chatDbo.id)
                val messageId = messages.find { it.clientId == updateMessageDto.messageId }?.id ?: return@forEach
                messageRepository.updateMessage(
                    messageId = messageId,
                    isRead = updateMessageDto.isRead,
                    completedStatus = updateMessageDto.completedStatus
                )
            }

            request.deleteMessageIds?.let {
                messageRepository.deleteMessagesByIds(it)
            }
        } catch (e: Exception) {
            application.log.error("Error updating chat for user $userId", e)
            sendEvent(session, ChatWsEvent.Error(message = "Error updating chat"))
        }
    }

    private suspend fun processDeleteChatRequest(request: ChatWsRequest.DeleteChatRequest, session: WebSocketSession) {
        try {
            val chatId = request.chatId
            val chatDbo = chatRepository.getChatById(chatId)
                ?: return sendEvent(session, ChatWsEvent.Error(message = "Chat not found"))

            if (chatDbo.userId != userId) {
                return sendEvent(session, ChatWsEvent.Error(message = "You don't have access to this chat."))
            }

            chatRepository.deleteChat(chatId)
        } catch (e: Exception) {
            application.log.error("Error deleting chat for user $userId", e)
            sendEvent(session, ChatWsEvent.Error(message = "Error deleting chat"))
        }
    }

    private fun createPingPongJob(): Job {
        return scope.launch {
            while (isActive) {
                try {
                    val sessionsSnapshot = sessions.toList()
                    for (session in sessionsSnapshot) {
                        try {
                            if (System.currentTimeMillis() - session.lastActivity > SESSION_TIMEOUT_MS) {
                                try {
                                    session.session.close(CloseReason(CloseReason.Codes.NORMAL, "Session timeout"))
                                } catch (e: Exception) {
                                    application.log.debug("Error closing timed out session: ${e.message}")
                                }
                                removeConnection(session.session)
                            } else {
                                try {
                                    session.session.send(Frame.Text(defaultJson.encodeToString(PingPongMessage.Ping())))
                                } catch (e: ClosedSendChannelException) {
                                    removeConnection(session.session)
                                } catch (e: Exception) {
                                    application.log.debug("Error sending ping: ${e.message}")
                                    removeConnection(session.session)
                                }
                            }
                        } catch (e: Exception) {
                            application.log.error("Error processing session in ping job for user $userId", e)
                            try {
                                removeConnection(session.session)
                            } catch (removeException: Exception) {
                                application.log.debug("Error removing connection in ping job: ${removeException.message}")
                            }
                        }
                    }
                    delay(PING_INTERVAL_MS)
                } catch (e: Exception) {
                    application.log.error("Error in ping job for user $userId", e)
                    delay(PING_INTERVAL_MS) // Continue after error
                }
            }
        }
    }

    private fun createChatChangesMonitorJob(): Job {
        return scope.launch {
            while (isActive) {
                try {
                    // Initialize message monitor jobs for existing chats
                    try {
                        chatRepository.getChatsByUserId(userId).forEach { chat ->
                            if (!messageChangesMonitorJob.containsKey(chat.id)) {
                                messageChangesMonitorJob[chat.id] = createMessageChangesMonitorJob(
                                    chat.id, chat.clientId
                                )
                            }
                        }
                    } catch (e: Exception) {
                        application.log.error("Error initializing message monitor jobs for user $userId", e)
                    }

                    // Monitor chat changes
                    chatRepository.collectAllEventsForUserId(userId).collect { event ->
                        try {
                            when (event) {
                                is DatabaseEvent.Created -> {
                                    val chatId = event.new.id
                                    sendGlobalEvent(
                                        ChatWsEvent.ChatAdded(
                                            serverTimestamp = event.new.createdAt,
                                            chatId = event.new.clientId,
                                            addedChatDto = event.new.toAddedChatDto(mapper),
                                            addedMessages = messageRepository.getAllMessagesByChatId(chatId)
                                                .map { it.toAddedMessageDto(mapper) },
                                        )
                                    )
                                    messageChangesMonitorJob[chatId] = createMessageChangesMonitorJob(
                                        chatId, event.new.clientId
                                    )
                                }

                                is DatabaseEvent.Updated -> {
                                    when {
                                        !event.new.isDeleted -> {
                                            sendGlobalEvent(
                                                ChatWsEvent.ChatUpdated(
                                                    serverTimestamp = event.new.lastModifiedAt,
                                                    chatId = event.new.clientId,
                                                    updatedChatDto = event.new.toUpdatedChatDto(mapper),
                                                    updatedMessages = null
                                                )
                                            )
                                        }

                                        !event.old.isDeleted && event.new.isDeleted -> {
                                            val chatId = event.new.id
                                            sendGlobalEvent(
                                                ChatWsEvent.ChatDeleted(
                                                    serverTimestamp = event.new.deletedAt,
                                                    chatId = event.new.clientId,
                                                )
                                            )
                                            messageChangesMonitorJob[chatId]?.cancel()
                                            messageChangesMonitorJob.remove(chatId)
                                        }
                                    }
                                }

                                is DatabaseEvent.Deleted -> {
                                    val chatId = event.old.id
                                    sendGlobalEvent(
                                        ChatWsEvent.ChatDeleted(
                                            serverTimestamp = UtcTimestamp.now(),
                                            chatId = event.old.clientId,
                                        )
                                    )
                                    messageChangesMonitorJob[chatId]?.cancel()
                                    messageChangesMonitorJob.remove(chatId)
                                }
                            }
                        } catch (e: Exception) {
                            application.log.error("Error processing chat event for user $userId", e)
                        }
                    }
                    break // Exit retry loop on successful completion
                } catch (e: Exception) {
                    application.log.error(
                        "Chat monitor failed for user $userId, retrying in ${MONITOR_RETRY_DELAY_MS}ms",
                        e
                    )
                    delay(MONITOR_RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun createMessageChangesMonitorJob(chatId: String, chatClientId: String): Job {
        return scope.launch {
            var addedMessagesBuffer: InactivityBuffer<MessageDbo>? = null
            var updatedMessagesBuffer: InactivityBuffer<MessageDbo>? = null
            var deletedMessagesBuffer: InactivityBuffer<String>? = null

            try {
                addedMessagesBuffer = InactivityBuffer { messageDbos ->
                    sendGlobalEvent(
                        ChatWsEvent.ChatUpdated(
                            chatId = chatClientId,
                            serverTimestamp = UtcTimestamp.now(),
                            addedMessages = messageDbos.map { it.toAddedMessageDto(mapper) },
                        )
                    )
                }
                updatedMessagesBuffer = InactivityBuffer { messageDbos ->
                    sendGlobalEvent(
                        ChatWsEvent.ChatUpdated(
                            chatId = chatClientId,
                            serverTimestamp = UtcTimestamp.now(),
                            updatedMessages = messageDbos.map { it.toUpdatedMessageDto(mapper) },
                        )
                    )
                }
                deletedMessagesBuffer = InactivityBuffer { messageIds ->
                    sendGlobalEvent(
                        ChatWsEvent.ChatUpdated(
                            chatId = chatClientId,
                            serverTimestamp = UtcTimestamp.now(),
                            deletedMessagesIds = messageIds,
                        )
                    )
                }

                while (isActive) {
                    try {
                        messageRepository.collectAllEventsForChatId(chatId).collect { event ->
                            try {
                                when (event) {
                                    is DatabaseEvent.Created -> {
                                        addedMessagesBuffer.add(event.new)
                                        if (event.new.completedStatus != CompletedStatus.COMPLETED.code) {
                                            messagesToBeCompleted.send(event.new.id)
                                        }
                                    }

                                    is DatabaseEvent.Updated -> {
                                        when {
                                            !event.new.isDeleted -> {
                                                updatedMessagesBuffer.add(event.new)
                                                if (event.old.completedStatus != CompletedStatus.NOT_COMPLETED.code &&
                                                    event.new.completedStatus == CompletedStatus.NOT_COMPLETED.code
                                                ) {
                                                    messagesToBeCompleted.send(event.new.id)
                                                }
                                            }

                                            !event.old.isDeleted && event.new.isDeleted -> {
                                                deletedMessagesBuffer.add(event.new.id)
                                            }
                                        }
                                    }

                                    is DatabaseEvent.Deleted -> {
                                        deletedMessagesBuffer.add(event.old.id)
                                    }
                                }
                            } catch (e: Exception) {
                                application.log.error("Error processing message event for chat $chatId", e)
                            }
                        }
                        break // Exit retry loop on successful completion
                    } catch (e: Exception) {
                        application.log.error(
                            "Message monitor failed for chat $chatId, retrying in ${MONITOR_RETRY_DELAY_MS}ms",
                            e
                        )
                        delay(MONITOR_RETRY_DELAY_MS)
                    }
                }
            } finally {
                try {
                    addedMessagesBuffer?.close()
                } catch (e: Exception) {
                    application.log.debug("Error closing added messages buffer: ${e.message}")
                }
                try {
                    updatedMessagesBuffer?.close()
                } catch (e: Exception) {
                    application.log.debug("Error closing updated messages buffer: ${e.message}")
                }
                try {
                    deletedMessagesBuffer?.close()
                } catch (e: Exception) {
                    application.log.debug("Error closing deleted messages buffer: ${e.message}")
                }
            }
        }
    }

    private fun createMessageCompletionJob(): Job {
        return scope.launch {
            try {
                messagesToBeCompleted.consumeEach { messageId ->
                    try {
                        val messageDbo = messageRepository.getMessageById(messageId) ?: return@consumeEach
                        if (messageDbo.isDeleted || messageDbo.completedStatus != CompletedStatus.NOT_COMPLETED.code) {
                            return@consumeEach
                        }

                        val chatDbo = chatRepository.getChatById(messageDbo.chatId)
                        if (chatDbo == null) {
                            messageRepository.updateMessage(messageId, completedStatus = CompletedStatus.FAILED.code)
                            return@consumeEach
                        }

                        val characterDbo = characterRepository.getCharacter(chatDbo.characterIds.first())
                        if (characterDbo == null) {
                            messageRepository.updateMessage(messageId, completedStatus = CompletedStatus.FAILED.code)
                            return@consumeEach
                        }

                        runCatching {
                            AiMessageGeneratorUtil.generateAiMessage(
                                characterName = characterDbo.name,
                                characterPrompt = characterDbo.prompt,
                                messagesHistory = messageRepository.getAllMessagesByChatId(chatId = chatDbo.id)
                                    .filter { !it.isDeleted }.takeLast(50)
                            )
                        }.onSuccess { message ->
                            messageRepository.updateMessage(
                                messageId = messageId,
                                isRead = false,
                                completedStatus = CompletedStatus.COMPLETED.code,
                                text = message,
                            )
                        }.onFailure { error ->
                            messageRepository.updateMessage(messageId, completedStatus = CompletedStatus.FAILED.code)
                            application.log.error("Error generating AI message for message $messageId", error)
                        }
                    } catch (e: Exception) {
                        application.log.error("Error processing message completion for message $messageId", e)
                        try {
                            messageRepository.updateMessage(messageId, completedStatus = CompletedStatus.FAILED.code)
                        } catch (updateException: Exception) {
                            application.log.error(
                                "Error updating message status to failed for message $messageId",
                                updateException
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                application.log.error("Error in message completion job for user $userId", e)
            }
        }
    }

    private suspend fun completeUncompletedMessages() {
        try {
            chatRepository.getChatsByUserId(userId).map { it.id }.forEach { chatId ->
                try {
                    messageRepository.getAllMessagesByChatId(chatId).forEach { messageDbo ->
                        if (messageDbo.completedStatus != CompletedStatus.COMPLETED.code && !messageDbo.isDeleted) {
                            messagesToBeCompleted.send(messageDbo.id)
                        }
                    }
                } catch (e: Exception) {
                    application.log.error("Error processing uncompleted messages for chat $chatId", e)
                }
            }
        } catch (e: Exception) {
            application.log.error("Error getting chats for completing uncompleted messages for user $userId", e)
        }
    }

    private suspend fun sendGlobalEvent(event: ChatWsEvent) {
        try {
            for (sessionInfo in sessions.toList()) {
                sendEvent(sessionInfo.session, event)
            }
        } catch (e: Exception) {
            application.log.error("Error sending global event for user $userId", e)
        }
    }

    private suspend fun sendEvent(session: WebSocketSession, event: ChatWsEvent) {
        try {
            session.send(Frame.Text(defaultJson.encodeToString(event)))
        } catch (e: ClosedSendChannelException) {
            application.log.debug("WebSocket send channel closed, removing connection")
            removeConnection(session)
        } catch (e: Exception) {
            application.log.debug("Error sending event to WebSocket: ${e.message}")
            removeConnection(session)
        }
    }
}