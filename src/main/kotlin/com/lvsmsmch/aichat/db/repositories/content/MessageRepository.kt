package com.lvsmsmch.aichat.db.repositories.content

import com.lvsmsmch.aichat.db.repositories._utils.MatchPositions
import com.lvsmsmch.aichat.db.repositories._utils.SearchUtil
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class MessageDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val chatId: String,
    val isSentByUser: Boolean = false,
    val senderId: String,       // User id or Character id
    val text: String,
    val isRead: Boolean = false,
    val editedAt: UtcTimestamp? = null,
    val metadata: Map<String, String> = emptyMap()
)

class MessageRepository(
    private val collection: CoroutineCollection<MessageDbo>,
    private val onMessageAdded: (characterId: String) -> Unit,
    private val getAllChatIdsForUser: (userId: String) -> List<String>,
    private val getCharacterIdByChatId: (chatId: String) -> String,
) {

    init {
        initializeIndexes()
    }

    /**
     * Initialize indexes for the collection
     */
    private fun initializeIndexes() {
        runBlocking {
            // Index for chat messages ordered by creation time
            collection.ensureIndex(
                ascending(
                    MessageDbo::chatId,
                    MessageDbo::createdAt
                )
            )
        }
    }

    /**
     * Search for messages by content across a user's chats
     */
    suspend fun searchMessagesByContentInAllChats(
        searchText: String,
        userId: String,
    ): Map<MessageDbo, MatchPositions> {
        val chatIds = getAllChatIdsForUser(userId)

        if (chatIds.isEmpty()) {
            return emptyMap()
        }

        val filter = and(
            MessageDbo::chatId.`in`(chatIds),
            MessageDbo::text.regex(".*$searchText.*", "i")
        )

        return collection.find(filter)
            .sort(descending(MessageDbo::createdAt))
            .toList()
            .associateWith { SearchUtil.findAllMatches(it.text, searchText) }
    }


    /**
     * Search messages in a specific chat by content text
     */
    suspend fun searchMessagesInChat(
        searchText: String,
        chatId: String,
    ): Map<MessageDbo, MatchPositions> {

        val filter = and(
            MessageDbo::chatId eq chatId,
            MessageDbo::text.regex(".*$searchText.*", "i")
        )

        return collection.find(filter)
            .sort(descending(MessageDbo::createdAt))
            .toList()
            .associateWith { SearchUtil.findAllMatches(it.text, searchText) }
    }

    /**
     * Add a new message to a chat
     */
    suspend fun addMessage(
        chatId: String,
        isSentByUser: Boolean,
        content: String,
        metadata: Map<String, String> = emptyMap()
    ): MessageDbo {
        val message = MessageDbo(
            chatId = chatId,
            isSentByUser = isSentByUser,
            senderId = "",
            text = content,
            metadata = metadata
        )

        collection.insertOne(message)
        onMessageAdded(getCharacterIdByChatId(message.chatId))
        return message
    }

    /**
     * Get a message by its ID
     */
    suspend fun getMessageById(messageId: String): MessageDbo? {
        return collection.findOneById(messageId)
    }

    /**
     * Get messages from a chat with pagination
     */
    suspend fun getMessagesByChatId(
        chatId: String,
        limit: Int = 50,
        skip: Int = 0,
        descending: Boolean = true
    ): List<MessageDbo> {
        val sort = if (descending) {
            descending(MessageDbo::createdAt)
        } else {
            ascending(MessageDbo::createdAt)
        }

        return collection.find(MessageDbo::chatId eq chatId)
            .sort(sort)
            .skip(skip)
            .limit(limit)
            .toList()
    }

    /**
     * Edit a message
     */
    suspend fun editMessage(messageId: String, newContent: String): Boolean {
        val now = UtcTimestamp.now()

        val updateResult = collection.updateOneById(
            messageId,
            combine(
                setValue(MessageDbo::text, newContent),
                setValue(MessageDbo::editedAt, now)
            )
        )

        return updateResult.modifiedCount > 0
    }

    /**
     * Delete a message
     */
    suspend fun deleteMessage(messageId: String): Boolean {
        return collection.deleteOneById(messageId).deletedCount > 0
    }

    /**
     * Delete multiple messages by their IDs
     *
     * @param messageIds List of message IDs to delete
     * @return The number of messages successfully deleted
     */
    suspend fun deleteMessagesByIds(messageIds: List<String>): Long {
        if (messageIds.isEmpty()) {
            return 0
        }

        val deleteResult = collection.deleteMany(MessageDbo::id.`in`(messageIds))
        return deleteResult.deletedCount
    }

    /**
     * Delete all messages in a chat
     */
    suspend fun deleteAllMessagesInChat(chatId: String): Long {
        val deleteResult = collection.deleteMany(MessageDbo::chatId eq chatId)
        return deleteResult.deletedCount
    }

    /**
     * Count total messages in a chat
     */
    suspend fun countMessagesByChatId(chatId: String): Long {
        return collection.countDocuments(MessageDbo::chatId eq chatId)
    }


    /**
     * Count total messages in a chat
     */
    suspend fun countUnreadMessagesInChat(chatId: String): Long {
        return collection.countDocuments(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::isRead eq false
            )
        )
    }


    /**
     * Get the most recent message in a chat
     */
    suspend fun getLastMessageInChat(chatId: String): MessageDbo? {
        return collection.find(MessageDbo::chatId eq chatId)
            .sort(descending(MessageDbo::createdAt))
            .limit(1)
            .first()
    }

    /**
     * Update message metadata
     */
    suspend fun updateMessageMetadata(messageId: String, metadata: Map<String, String>): Boolean {
        val updateResult = collection.updateOneById(
            messageId,
            setValue(MessageDbo::metadata, metadata)
        )
        return updateResult.modifiedCount > 0
    }

    /**
     * Mark a message as read
     */
    suspend fun markMessageAsRead(messageId: String): Boolean {
        val updateResult = collection.updateOneById(messageId, setValue(MessageDbo::isRead, true))
        return updateResult.modifiedCount > 0
    }

    /**
     * Mark all character messages in a chat as read
     */
    suspend fun markAllCharacterMessagesAsRead(chatId: String): Long {
        val filter = and(
            MessageDbo::chatId eq chatId,
            MessageDbo::isSentByUser eq false,
            MessageDbo::isRead eq false
        )
        val updateResult = collection.updateMany(filter, setValue(MessageDbo::isRead, true))
        return updateResult.modifiedCount
    }
}