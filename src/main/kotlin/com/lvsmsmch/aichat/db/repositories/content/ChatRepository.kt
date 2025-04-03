package com.lvsmsmch.aichat.db.repositories.content

import com.lvsmsmch.aichat.db.repositories._utils.Matches
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
    val characterName: String,
    val characterDescription: String,
    val characterPrompt: String,
    val characterPicUrl: String,
    val isChatMuted: Boolean,
)

class ChatRepository(
    private val collection: CoroutineCollection<ChatDbo>,
    private val messageCollection: CoroutineCollection<MessageDbo>
) {

    init {
        initializeIndexes()
    }

    /**
     * Initialize indexes for the collection
     */
    private fun initializeIndexes() {
        runBlocking {
            // Basic index for filtering by userId
            collection.ensureIndex(
                ascending(ChatDbo::userId)
            )

            // Index for character name searches
            collection.ensureIndex(
                ascending(
                    ChatDbo::userId,
                    ChatDbo::characterName
                )
            )
        }
    }

    /**
     * Create a new chat
     */
    suspend fun createChat(
        userId: String,
        characterName: String,
        characterDescription: String,
        characterPrompt: String,
        characterPicUrl: String,
        isChatMuted: Boolean,
    ): ChatDbo {
        val chat = ChatDbo(
            userId = userId,
            characterName = characterName,
            characterDescription = characterDescription,
            characterPrompt = characterPrompt,
            characterPicUrl = characterPicUrl,
            isChatMuted = isChatMuted
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
     * Get a chat with its last message by ID
     */
    suspend fun getChatWithLastMessageById(chatId: String): ChatDbo? {
        return getChatById(chatId)
    }

    /**
     * Get all chats for a user with their last messages
     */
    suspend fun getChatsByUserId(userId: String): List<ChatDbo> {
        return collection.find(ChatDbo::userId eq userId).toList()
    }

    /**
     * Search for chats by character name
     */
    suspend fun searchChatsByCharacterName(
        searchText: String,
        userId: String
    ): Map<ChatDbo, Matches> {
        val filter = and(
            ChatDbo::userId eq userId,
            ChatDbo::characterName.regex(".*$searchText.*", "i")
        )

        return collection.find(filter)
            .toList()
            .associateWith { Matches(SearchUtil.findAllMatches(it.characterName, searchText)) }
            .filter { it.value.matches.isNotEmpty() }
    }

    /**
     * Retrieve chat dbo list by ids
     */
    suspend fun getChatByIds(chatIds: List<String>): List<ChatDbo> {
        if (chatIds.isEmpty()) {
            return emptyList()
        }

        return collection.find(ChatDbo::id.`in`(chatIds)).toList()
    }

    /**
     * Count total messages in a chat
     */
    suspend fun countMessagesByChatId(chatId: String): Long {
        return messageCollection.countDocuments(MessageDbo::chatId eq chatId)
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
}