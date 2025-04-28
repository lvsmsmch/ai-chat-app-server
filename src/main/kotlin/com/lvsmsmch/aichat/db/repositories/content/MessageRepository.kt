package com.lvsmsmch.aichat.db.repositories.content

import com.lvsmsmch.aichat.db.repositories._utils.ChangeEvent
import com.lvsmsmch.aichat.utils.MatchPositions
import com.lvsmsmch.aichat.utils.SearchUtil
import com.lvsmsmch.aichat.db.repositories._utils.watchAsFlow
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class MessageDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val editedAt: UtcTimestamp? = null,
    val chatId: String,
    val isSentByUser: Boolean = false,
    val isLoading: Boolean = false,
    val isFailed: Boolean = false,
    val isRead: Boolean = false,
    val text: String,
    val imageUrl: String? = null,
)

class MessageRepository(
    private val collection: CoroutineCollection<MessageDbo>,
    private val onMessageAddedForChat: (chatId: String) -> Unit,
    private val onNewLastMessageInChat: (chatId: String, messageDbo: MessageDbo?) -> Unit,
    private val onNewUnreadCountForChat: (chatId: String, unreadCount: Int) -> Unit,
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
     * FLOW
     */



    private val changeEventsFlow: SharedFlow<ChangeEvent<MessageDbo>> by lazy {
        collection
            .watchAsFlow()
            .shareIn(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                started = SharingStarted.Eagerly,
                replay = 0
            )
    }

    fun collectAllEventsForChatId(chatId: String): Flow<ChangeEvent<MessageDbo>> {
        return changeEventsFlow.filter {
            when (it) {
                is ChangeEvent.Created -> it.new.chatId == chatId
                is ChangeEvent.Updated -> it.new.chatId == chatId
                is ChangeEvent.Deleted -> it.old.chatId == chatId
            }
        }
    }



    /**
     * CREATE
     */
    suspend fun addMessage(
        chatId: String,
        isSentByUser: Boolean,
        isLoading: Boolean,
        isFailed: Boolean,
        content: String,
        imageUrl: String? = null,
    ): MessageDbo {
        val message = MessageDbo(
            chatId = chatId,
            isSentByUser = isSentByUser,
            isLoading = isLoading,
            isFailed = isFailed,
            text = content,
            imageUrl = imageUrl
        )
        collection.insertOne(message)
        onMessageAddedForChat(chatId)
        onNewLastMessageInChat(message.chatId, message)
        onNewUnreadCountForChat(message.chatId, countUnreadMessagesInChat(chatId))
        return message
    }


    /**
     * READ
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

    suspend fun getMessageById(messageId: String): MessageDbo? {
        return collection.findOneById(messageId)
    }

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

    suspend fun countMessagesInChat(chatId: String): Int {
        return collection.countDocuments(MessageDbo::chatId eq chatId).toInt()
    }

    suspend fun countUnreadMessagesInChat(chatId: String): Int {
        return collection.countDocuments(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::isRead eq false
            )
        ).toInt()
    }

    suspend fun getLastMessageInChat(chatId: String): MessageDbo? {
        return collection.find(MessageDbo::chatId eq chatId)
            .sort(descending(MessageDbo::createdAt))
            .limit(1)
            .first()
    }

    private suspend fun isMessageLastInItsChat(messageId: String): Boolean {
        val chatId = getMessageById(messageId)?.chatId ?: return false
        return getLastMessageInChat(chatId)?.id == messageId
    }

    suspend fun doAllMessagesBelongToChat(messageIds: List<String>, chatId: String): Boolean {
        if (messageIds.isEmpty()) return true

        // Count messages that match both the IDs and the chatId
        val matchingCount = collection.countDocuments(
            and(
                MessageDbo::id.`in`(messageIds),
                MessageDbo::chatId eq chatId
            )
        ).toInt()

        // If all messages belong to the chat, the counts should match
        return matchingCount == messageIds.size
    }

    /**
     * UPDATE
     */

    suspend fun updateMessage(
        messageId: String,
        isLoading: Boolean? = null,
        isFailed: Boolean? = null,
        text: String? = null,
        imageUrl: String? = null,
    ) {
        collection.findOneById(messageId) ?: return
        val updates = mutableListOf<Bson>()
        isLoading?.let { updates.add(setValue(MessageDbo::isLoading, it)) }
        isFailed?.let { updates.add(setValue(MessageDbo::isFailed, it)) }
        text?.let { updates.add(setValue(MessageDbo::text, it)) }
        imageUrl?.let { updates.add(setValue(MessageDbo::imageUrl, it)) }
        if (updates.isEmpty()) return // Nothing to update
        collection.updateOneById(messageId, combine(*updates.toTypedArray()))
        if (isMessageLastInItsChat(messageId)) {
            getMessageById(messageId)?.let { onNewLastMessageInChat(it.chatId, it) }
        }
    }

    suspend fun markMessageAsRead(messageId: String) {
        val chatId = getMessageById(messageId)?.chatId ?: return
        collection.updateOneById(messageId, setValue(MessageDbo::isRead, true))
        onNewUnreadCountForChat(chatId, countUnreadMessagesInChat(chatId))
    }

    suspend fun markAllCharacterMessagesAsRead(chatId: String) {
        val filter = and(
            MessageDbo::chatId eq chatId,
            MessageDbo::isSentByUser eq false,
            MessageDbo::isRead eq false
        )
        collection.updateMany(filter, setValue(MessageDbo::isRead, true))
        onNewUnreadCountForChat(chatId, 0)
    }


    /**
     * DELETE
     */
    suspend fun deleteMessage(messageId: String) {
        deleteMessagesByIds(listOf(messageId))
    }

    suspend fun deleteMessagesByIds(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val chatId = getMessageById(messageIds.first())?.chatId ?: return
        collection.deleteMany(MessageDbo::id.`in`(messageIds))
        onNewLastMessageInChat(chatId, getLastMessageInChat(chatId))
        onNewUnreadCountForChat(chatId, countUnreadMessagesInChat(chatId))
    }

    suspend fun deleteAllMessagesInChat(chatId: String) {
        collection.deleteMany(MessageDbo::chatId eq chatId)
        onNewLastMessageInChat(chatId, null)
        onNewUnreadCountForChat(chatId, 0)
    }
}