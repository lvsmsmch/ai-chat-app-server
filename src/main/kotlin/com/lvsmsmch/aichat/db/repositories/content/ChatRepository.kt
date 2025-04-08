package com.lvsmsmch.aichat.db.repositories.content

import com.lvsmsmch.aichat.db.repositories._utils.MatchPositions
import com.lvsmsmch.aichat.db.repositories._utils.SearchUtil
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class ChatDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val userId: String,
    val characterId: String,
    val characterName: String,
    val characterDescription: String,
    val characterPrompt: String,
    val characterPicUrl: String,
    val isChatMuted: Boolean,
)

class ChatRepository(
    private val collection: CoroutineCollection<ChatDbo>,
    private val countMessagesByChatId: (chatId: String) -> Int,
) {

    init {
        initializeIndexes()
    }

    /**
     * Initialize indexes for the collection
     */
    private fun initializeIndexes() {
        runBlocking {
            collection.ensureIndex(ascending(ChatDbo::userId))
            collection.ensureIndex(ascending(ChatDbo::characterId))
            collection.ensureIndex(ascending(ChatDbo::userId, ChatDbo::characterName))
        }
    }


    /**
     * Search for chats by character name
     */
    suspend fun searchChatsByCharacterName(
        searchText: String,
        userId: String
    ): Map<ChatDbo, MatchPositions> {
        val filter = and(
            ChatDbo::userId eq userId,
            ChatDbo::characterName.regex(".*$searchText.*", "i")
        )

        return collection.find(filter)
            .toList()
            .associateWith { SearchUtil.findAllMatches(it.characterName, searchText) }
            .filter { it.value.positions.isNotEmpty() }
    }


    /**
     * Create a new chat
     */
    suspend fun createChat(
        userId: String,
        characterId: String,
        characterName: String,
        characterDescription: String,
        characterPrompt: String,
        characterPicUrl: String,
        isChatMuted: Boolean,
    ): ChatDbo {
        val chat = ChatDbo(
            userId = userId,
            characterId = characterId,
            characterName = characterName,
            characterDescription = characterDescription,
            characterPrompt = characterPrompt,
            characterPicUrl = characterPicUrl,
            isChatMuted = isChatMuted,
        )
        collection.insertOne(chat)
        return chat
    }

    /**
     * Get a chat by its ID
     */
    suspend fun getChatById(chatId: String): ChatDbo? {
        return collection.findOneById(chatId)
    }

    /**
     * Get all chats for a user with their last messages
     */
    suspend fun getChatsByUserId(userId: String): List<ChatDbo> {
        return collection.find(ChatDbo::userId eq userId).toList()
    }

    /**
     * Retrieve chat dbo list by ids
     */
    suspend fun getChatsByIds(chatIds: List<String>): List<ChatDbo> {
        if (chatIds.isEmpty()) {
            return emptyList()
        }

        return collection.find(ChatDbo::id.`in`(chatIds)).toList()
    }

    /**
     * Delete a chat
     */
    suspend fun deleteChat(chatId: String): Boolean {
        return collection.deleteOneById(chatId).deletedCount > 0
    }

    /**
     * Count total chats for a user
     */
    suspend fun countChatsByUserId(userId: String): Long {
        val filter = ChatDbo::userId eq userId
        return collection.countDocuments(filter)
    }


    suspend fun getUserIdByChatId(chatId: String): String {
        return collection.findOneById(chatId)?.userId ?: ""
    }

    suspend fun getCharacterIdByChatId(chatId: String): String {
        return collection.findOneById(chatId)?.characterId ?: ""
    }

    suspend fun getAllChatIdsForUser(userId: String): List<String> {
        return collection
            .find(ChatDbo::userId eq userId)
            .projection(ChatDbo::id)
            .toList()
            .map { it.id }
    }
}