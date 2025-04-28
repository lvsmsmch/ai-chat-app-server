package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.ChangeEvent
import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.toChatDto
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.network.dto_objects.ChatDto
import com.lvsmsmch.aichat.utils.InactivityBuffer
import com.lvsmsmch.aichat.utils.defaultJson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.logging.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
sealed class ChatsWsRequest {
}

@Serializable
sealed class ChatsWsEvent {
    @Serializable
    @SerialName("chat_added")
    data class ChatAdded(
        val chatDto: ChatDto,
        val unreadChats: Int
    ) : ChatsWsEvent()

    @Serializable
    @SerialName("chat_changed")
    data class ChatChanged(
        val chatDto: ChatDto,
        val unreadChats: Int
    ) : ChatsWsEvent()

    @Serializable
    @SerialName("chat_range_deleted")
    data class ChatRangeDeleted(
        val chatIds: List<String>,
        val unreadChats: Int
    ) : ChatsWsEvent()


    @Serializable
    @SerialName("error")
    data class Error(
        val message: String
    ) : ChatsWsEvent()
}

fun Routing.configureNotificationsWebSocketRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
    mapper: Mapper
) {
    webSocket("/chats/ws") {
        var collectionJob: Job? = null
        var pingPongManager: PingPongManager? = null
        try {
            val sessionDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: Exception) {
                return@webSocket call.respond(HttpStatusCode.Unauthorized, e.message.toString())
            }

            collectionJob = collectChatsChanges(
                wsSession = this,
                chatRepository = chatRepository,
                userId = sessionDbo.userId,
                mapper = mapper
            )

            pingPongManager = PingPongManager(webSocketSession = this)

            incoming.consumeEach { _ ->
                try {
                    pingPongManager.updateActivity()
                } catch (e: ClosedReceiveChannelException) {
                    throw e
                } catch (e: Exception) {
                    application.log.error(e)
                    sendEvent(ChatsWsEvent.Error(message = "Error processing request: ${e.message}"))
                }
            }

        } catch (e: ClosedReceiveChannelException) {
            // Normal close, no need to do anything
        } catch (e: Exception) {
            application.log.error(e)
            sendEvent(ChatsWsEvent.Error(message = "Internal server error"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message ?: "Unknown error"))
        } finally {
            collectionJob?.cancel()
            pingPongManager?.stop()
        }
    }
}

private fun CoroutineScope.collectChatsChanges(
    wsSession: WebSocketSession,
    chatRepository: ChatRepository,
    userId: String,
    mapper: Mapper
): Job {
    return launch {

        // Create the deletion buffer
        val deletionBuffer = InactivityBuffer(
            inactivityTimeoutMs = 50,
            checkIntervalMs = 20,
            onBatchReady = { chatIds ->
                wsSession.sendEvent(
                    ChatsWsEvent.ChatRangeDeleted(
                        chatIds = chatIds,
                        unreadChats = chatRepository.countUnreadChatsByUserId(userId)
                    )
                )
            }
        )

        chatRepository.collectAllEventsForUserId(userId).collect {
            when (it) {
                is ChangeEvent.Created -> {
                    wsSession.sendEvent(
                        ChatsWsEvent.ChatAdded(
                            chatDto = it.new.toChatDto(mapper),
                            unreadChats = chatRepository.countUnreadChatsByUserId(userId)
                        )
                    )
                }

                is ChangeEvent.Updated -> {
                    wsSession.sendEvent(
                        ChatsWsEvent.ChatChanged(
                            chatDto = it.new.toChatDto(mapper),
                            unreadChats = chatRepository.countUnreadChatsByUserId(userId)
                        )
                    )
                }

                is ChangeEvent.Deleted -> {
                    deletionBuffer.add(it.old.id)
                }
            }
        }

        try {
            awaitCancellation()
        } finally {
            deletionBuffer.close()
        }
    }
}

private suspend fun WebSocketSession.sendEvent(event: ChatsWsEvent) {
    send(Frame.Text(defaultJson.encodeToString(event)))
}