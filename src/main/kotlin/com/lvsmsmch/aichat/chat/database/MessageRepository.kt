package com.lvsmsmch.aichat.chat.database

import com.lvsmsmch.aichat.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection
import java.util.*

@Serializable
enum class CompletedStatus(val code: String) {
    NOT_COMPLETED("not_completed"),
    COMPLETED("completed"),
    FAILED("failed");
}

@Serializable
data class MessageDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val clientId: String = UUID.randomUUID().toString(),
    val lastModifiedAt: UtcTimestamp = UtcTimestamp.now(),
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val chatId: String,
    val senderId: String,
    val isSentByUser: Boolean = false,
    val text: String,
    val imageUrl: String? = null,
    val isRead: Boolean = false,
    val completedStatus: String,
    val isCompleted: Boolean = false,
    val isFailedToComplete: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: UtcTimestamp = UtcTimestamp.now(),
)

class MessageRepository(
    private val collection: CoroutineCollection<MessageDbo>
) {

    /**
     * Initialize indexes for the collection
     */

    init {
        runBlocking {
            // Index for chat messages ordered by creation time
            collection.ensureIndex(
                ascending(
                    MessageDbo::chatId,
                    MessageDbo::createdAt
                )
            )
            collection.ensureIndex(MessageDbo::clientId)
        }
    }


    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    fun collectAllEventsForUserId(userId: String): Flow<DatabaseEvent<MessageDbo>> {
        return databaseEventsFlow.filter { it.latestObject.senderId == userId }
    }

    fun collectAllEventsForChatIds(chatIds: List<String>): Flow<DatabaseEvent<MessageDbo>> {
        return databaseEventsFlow.filter { event -> event.latestObject.chatId in chatIds }
    }

    fun collectAllEventsForChatId(chatId: String): Flow<DatabaseEvent<MessageDbo>> {
        return databaseEventsFlow.filter { event -> event.latestObject.chatId == chatId }
    }

    /**
     * CREATE
     */

    suspend fun insertMessage(messageDbo: MessageDbo) {
        collection.insertOne(messageDbo)
    }

    /**
     * READ
     */

    suspend fun getMessagesCreatedAfter(chatId: String, timestamp: UtcTimestamp): List<MessageDbo> {
        return collection.find(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::createdAt gt timestamp,
                MessageDbo::lastModifiedAt gt timestamp,
                MessageDbo::isDeleted eq false
            )
        ).sort(ascending(MessageDbo::createdAt)).toList()
    }

    suspend fun getMessagesUpdatedAfter(chatId: String, timestamp: UtcTimestamp): List<MessageDbo> {
        return collection.find(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::createdAt lte timestamp,
                MessageDbo::lastModifiedAt gt timestamp,
                MessageDbo::isDeleted eq false
            )
        ).sort(ascending(MessageDbo::createdAt)).toList()
    }

    suspend fun getDeletedMessageIdsAfter(chatId: String, timestamp: UtcTimestamp): List<String> {
        return collection.find(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::deletedAt gt timestamp,
                MessageDbo::isDeleted eq true
            )
        ).toList().map { it.id }
    }

    suspend fun getMessageById(messageId: String): MessageDbo? {
        return collection.findOneById(messageId)
    }

    suspend fun getAllMessagesByChatId(
        chatId: String,
        descending: Boolean = true,
    ): List<MessageDbo> {
        val sort = if (descending) {
            descending(MessageDbo::createdAt)
        } else {
            ascending(MessageDbo::createdAt)
        }

        return collection.find(and(MessageDbo::chatId eq chatId))
            .sort(sort)
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
        imageUrl: String? = null,
        isRead: Boolean? = null,
        completedStatus: String? = null,
        text: String? = null,
    ) {
        collection.findOneById(messageId) ?: return
        val updates = mutableListOf<Bson>()
        imageUrl?.let { updates.add(setValue(MessageDbo::imageUrl, it)) }
        isRead?.let { updates.add(setValue(MessageDbo::isRead, it)) }
        completedStatus?.let { updates.add(setValue(MessageDbo::completedStatus, it)) }
        text?.let { updates.add(setValue(MessageDbo::text, it)) }
        if (updates.isEmpty()) return // Nothing to update
        collection.updateOneById(
            messageId,
            combine(
                *updates.toTypedArray(),
                setValue(MessageDbo::lastModifiedAt, UtcTimestamp.now())
            )
        )
    }


    /**
     * DELETE
     */
    suspend fun deleteMessage(messageId: String) {
        deleteMessagesByIds(listOf(messageId))
    }

    suspend fun deleteMessagesByIds(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        collection.updateMany(
            MessageDbo::id `in` messageIds,
            combine(
                setValue(MessageDbo::isDeleted, true),
                setValue(MessageDbo::deletedAt, UtcTimestamp.now()),
                setValue(MessageDbo::lastModifiedAt, UtcTimestamp.now())
            )
        )
    }

    suspend fun deleteAllMessagesInChat(chatId: String) {
        collection.updateMany(
            MessageDbo::chatId eq chatId,
            combine(
                setValue(MessageDbo::isDeleted, true),
                setValue(MessageDbo::deletedAt, UtcTimestamp.now()),
                setValue(MessageDbo::lastModifiedAt, UtcTimestamp.now())
            )
        )
    }
}