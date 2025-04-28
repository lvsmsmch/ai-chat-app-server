package com.lvsmsmch.aichat.network.routing.chat

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.utils.SearchUtil
import com.lvsmsmch.aichat.db.repositories._utils.toChatDto
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.CharacterRepository
import com.lvsmsmch.aichat.db.repositories.content.ChatRepository
import com.lvsmsmch.aichat.db.repositories.content.MessageRepository
import com.lvsmsmch.aichat.network.dto_objects.ChatDto
import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureGetChatsRouting(
    sessionRepository: SessionRepository,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    characterRepository: CharacterRepository,
    mapper: Mapper
) {

    @Serializable
    data class ChatsResponse(
        val chats: List<ChatDto>,
        val totalCount: Int
    )

    get("/chats") {
        try {
            val sessionDbo = try {
                sessionRepository.verifyToken(call)
            } catch (e: UnauthorizedException) {
                return@get call.respond(HttpStatusCode.Unauthorized, e.message)
            }

            val searchQuery = call.request.queryParameters["searchQuery"] ?: ""
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0

            var totalCount: Int
            val results = chatRepository.getChatsByUserId(sessionDbo.userId)
                .filter {
                    if (searchQuery.isBlank()) return@filter true
                    val characterName = characterRepository.getCharacter(it.characterId)?.name ?: return@filter false
                    return@filter SearchUtil.findAllMatches(characterName, searchQuery).positions.isNotEmpty()
                }
                .associateWith { messageRepository.getLastMessageInChat(it.id) }
                .asSequence()
                .sortedByDescending {
                    val chatCreateTime = it.key.createdAt.instant
                    val lastMessageTime = it.value?.createdAt?.instant
                    val lastChangeTime = maxOf(chatCreateTime, lastMessageTime ?: chatCreateTime)
                    lastChangeTime
                }
                .also { totalCount = it.count() }
                .drop(skip)
                .take(limit)
                .toList()
                .map { it.key.toChatDto(mapper) }

            call.respond(ChatsResponse(chats = results, totalCount = totalCount))
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(
                HttpStatusCode.InternalServerError,
                "An error occurred processing your request"
            )
        }
    }
}