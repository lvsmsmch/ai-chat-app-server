package com.lvsmsmch.aichat.chat.database

import com.lvsmsmch.aichat.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bson.conversions.Bson
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection


class MessageRepository(
    private val collection: CoroutineCollection<MessageDbo>
) {

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
            collection.ensureIndex(MessageDbo::senderId)
        }
    }

    /**
     * FLOW для стриминга обновлений сообщений
     */
    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    fun streamMessageUpdates(messageId: String): Flow<MessageUpdateEvent> {
        return databaseEventsFlow
            .filter { event ->
                when (event) {
                    is DatabaseEvent.Updated -> event.new.id == messageId
                    else -> false
                }
            }
            .map { event ->
                val message = (event as DatabaseEvent.Updated).new
                MessageUpdateEvent(
                    messageId = message.id,
                    newText = message.text,
                    isComplete = message.status == MessageStatus.COMPLETED.value,
                    isFailed = message.status == MessageStatus.FAILED.value
                )
            }
    }

    data class MessageUpdateEvent(
        val messageId: String,
        val newText: String,
        val isComplete: Boolean,
        val isFailed: Boolean
    )

    /**
     * CREATE
     */
    suspend fun insertMessage(messageDbo: MessageDbo) {
        collection.insertOne(messageDbo)
    }

    /**
     * READ - основные методы
     */
    suspend fun getMessageById(messageId: String): MessageDbo? {
        return collection.findOneById(messageId)
    }

    suspend fun findByClientId(clientMessageId: String): MessageDbo? {
        return collection.findOne(MessageDbo::clientId eq clientMessageId)
    }

    suspend fun getLastMessageInChat(chatId: String): MessageDbo? {
        return collection.find(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::isDeleted eq false
            )
        ).sort(descending(MessageDbo::createdAt))
            .limit(1)
            .first()
    }

    suspend fun countUnreadMessagesInChat(chatId: String, userId: String): Int {
        return collection.countDocuments(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::isRead eq false,
                MessageDbo::senderId ne userId, // Не считаем собственные сообщения
                MessageDbo::isDeleted eq false
            )
        ).toInt()
    }

    /**
     * READ - методы для курсорной пагинации
     */
    suspend fun getMessagesPaginated(
        chatId: String,
        cursor: String? = null,
        limit: Int = 50
    ): List<MessageDbo> {
        val baseFilter = and(
            MessageDbo::chatId eq chatId,
            MessageDbo::isDeleted eq false
        )

        val filter = if (cursor != null) {
            val cursorMessage = findByClientId(cursor) ?: return emptyList()
            and(baseFilter, MessageDbo::createdAt lt cursorMessage.createdAt)
        } else {
            baseFilter
        }

        return collection.find(filter)
            .sort(descending(MessageDbo::createdAt))
            .limit(limit)
            .toList()
    }

    /**
     * READ - методы для синхронизации по времени
     */

    suspend fun getMessagesCreatedBefore(chatId: String, timestamp: UtcTimestamp): List<MessageDbo> {
        return collection.find(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::createdAt lt timestamp,
                MessageDbo::isDeleted eq false
            )
        ).sort(ascending(MessageDbo::createdAt)).toList()
    }

    suspend fun getMessagesCreatedAfter(chatId: String, timestamp: UtcTimestamp): List<MessageDbo> {
        return collection.find(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::createdAt gt timestamp,
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
        ).toList().map { it.clientId } // Возвращаем clientId для клиента
    }

    /**
     * READ - методы для умной синхронизации в диапазоне
     */
    suspend fun getMessagesUpdatedInRange(
        chatId: String,
        afterTimestamp: UtcTimestamp,
        oldestTime: UtcTimestamp,
        newestTime: UtcTimestamp
    ): List<MessageDbo> {
        return collection.find(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::createdAt gte oldestTime,
                MessageDbo::createdAt lte newestTime,
                MessageDbo::lastModifiedAt gt afterTimestamp,
                MessageDbo::isDeleted eq false
            )
        ).sort(ascending(MessageDbo::createdAt)).toList()
    }

    suspend fun getDeletedMessageIdsInRange(
        chatId: String,
        afterTimestamp: UtcTimestamp,
        oldestTime: UtcTimestamp,
        newestTime: UtcTimestamp
    ): List<String> {
        return collection.find(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::createdAt gte oldestTime,
                MessageDbo::createdAt lte newestTime,
                MessageDbo::deletedAt gt afterTimestamp,
                MessageDbo::isDeleted eq true
            )
        ).toList().map { it.clientId }
    }

    suspend fun hasUpdatesBeforeTime(
        chatId: String,
        afterTimestamp: UtcTimestamp,
        beforeTime: UtcTimestamp
    ): Boolean {
        return collection.countDocuments(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::createdAt lt beforeTime,
                or(
                    MessageDbo::lastModifiedAt gt afterTimestamp,
                    and(
                        MessageDbo::isDeleted eq true,
                        MessageDbo::deletedAt gt afterTimestamp
                    )
                )
            )
        ) > 0
    }

    suspend fun hasMessagesAfterTime(chatId: String, afterTime: UtcTimestamp): Boolean {
        return collection.countDocuments(
            and(
                MessageDbo::chatId eq chatId,
                MessageDbo::createdAt gt afterTime,
                MessageDbo::isDeleted eq false
            )
        ) > 0
    }

    suspend fun hasChangesAfter(chatId: String, timestamp: UtcTimestamp): Boolean {
        return collection.countDocuments(
            and(
                MessageDbo::chatId eq chatId,
                or(
                    MessageDbo::createdAt gt timestamp,
                    MessageDbo::lastModifiedAt gt timestamp,
                    and(
                        MessageDbo::isDeleted eq true,
                        MessageDbo::deletedAt gt timestamp
                    )
                )
            )
        ) > 0
    }

    /**
     * READ - вспомогательные методы
     */
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

    suspend fun doAllMessageIdsBelongToChat(
        chatId: String,
        messageIds: List<String>
    ): Boolean {
        // todo
    }

    /**
     * UPDATE
     */
    suspend fun updateMessage(
        messageId: String,
        imageUrl: String? = null,
        isRead: Boolean? = null,
        status: String? = null,
        text: String? = null,
    ) {
        collection.findOneById(messageId) ?: return
        val updates = mutableListOf<Bson>()
        imageUrl?.let { updates.add(setValue(MessageDbo::imageUrl, it)) }
        isRead?.let { updates.add(setValue(MessageDbo::isRead, it)) }
        status?.let { updates.add(setValue(MessageDbo::status, it)) }
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

    suspend fun markMessagesAsRead(messageIds: List<String>, userId: String): Int {
        if (messageIds.isEmpty()) return 0

        val result = collection.updateMany(
            and(
                MessageDbo::clientId `in` messageIds,
                MessageDbo::isRead eq false
            ),
            combine(
                setValue(MessageDbo::isRead, true),
                setValue(MessageDbo::lastModifiedAt, UtcTimestamp.now())
            )
        )

        return result.modifiedCount.toInt()
    }

    /**
     * DELETE
     */

    suspend fun deleteMessagesByIds(chatId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        collection.updateMany(
            and(
                MessageDbo::id `in` messageIds,
                MessageDbo::chatId eq chatId,
            ),
            combine(
                setValue(MessageDbo::isDeleted, true),
                setValue(MessageDbo::deletedAt, UtcTimestamp.now()),
                setValue(MessageDbo::lastModifiedAt, UtcTimestamp.now())
            )
        )
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