package com.lvsmsmch.aichat.chat.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.EntityEvents
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toMessageDbo
import com.lvsmsmch.aichat.utils.DatabaseEvent
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class MessageRepository {

    private val table = Tables.Messages

    /**
     * Апдейты сообщений для SSE-стрима ответа персонажа. Раньше это был change
     * stream Mongo, теперь — внутрипроцессная шина: репозиторий публикует
     * событие сам, после успешной записи.
     */
    private val events = EntityEvents<MessageDbo>()

    val databaseEventsFlow: Flow<DatabaseEvent<MessageDbo>> get() = events.events

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
                    isFailed = message.status == MessageStatus.FAILED.value,
                    failReason = message.failReason,
                    imageUrl = message.imageUrl,
                    imageDebugInfo = message.imageDebugInfo,
                )
            }
            .distinctUntilChanged()
    }

    data class MessageUpdateEvent(
        val messageId: String,
        val newText: String,
        val isComplete: Boolean,
        val isFailed: Boolean,
        val failReason: String? = null,
        val imageUrl: String? = null,
        val imageDebugInfo: String? = null,
    )

    /**
     * Событие о вставке НЕ публикуется: вставка идёт внутри составной
     * транзакции (сообщение + счётчики), и событие ушло бы до коммита —
     * подписчик увидел бы запись, которой при откате не станет. Подписчиков у
     * Created сейчас нет: SSE-стрим слушает только апдейты.
     */
    suspend fun insertMessage(session: DbSession, messageDbo: MessageDbo) {
        dbQuery { table.insert { it.from(messageDbo) } }
    }

    suspend fun getMessageById(messageId: String): MessageDbo? = dbQuery {
        table.selectAll().where { table.id eq messageId }.limit(1).firstOrNull()?.toMessageDbo()
    }

    suspend fun findByClientId(clientMessageId: String): MessageDbo? = dbQuery {
        table.selectAll()
            .where { table.clientId eq clientMessageId }
            .limit(1)
            .firstOrNull()
            ?.toMessageDbo()
    }

    suspend fun getLastMessageInChat(chatId: String): MessageDbo? = dbQuery {
        table.selectAll()
            .where { (table.chatId eq chatId) and (table.isDeleted eq false) }
            .orderBy(table.createdAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toMessageDbo()
    }

    suspend fun countUnreadMessagesInChat(chatId: String, userId: String): Int = dbQuery {
        table.selectAll()
            .where {
                (table.chatId eq chatId) and
                    (table.isRead eq false) and
                    (table.senderId neq userId) and
                    (table.isDeleted eq false)
            }
            .count()
            .toInt()
    }

    suspend fun getMessagesPaginated(
        chatId: String,
        cursor: String? = null,
        limit: Int = 50,
    ): List<MessageDbo> {
        val cursorCreatedAt = cursor?.let { findByClientId(it)?.createdAt ?: return emptyList() }
        return dbQuery {
            table.selectAll()
                .where {
                    var op: Op<Boolean> = (table.chatId eq chatId) and (table.isDeleted eq false)
                    if (cursorCreatedAt != null) op = op and (table.createdAt less cursorCreatedAt)
                    op
                }
                .orderBy(table.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toMessageDbo() }
        }
    }

    suspend fun getMessagesCreatedBefore(chatId: String, timestamp: UtcTimestamp): List<MessageDbo> =
        dbQuery {
            table.selectAll()
                .where {
                    (table.chatId eq chatId) and
                        (table.createdAt less timestamp.toString()) and
                        (table.isDeleted eq false)
                }
                .orderBy(table.createdAt to SortOrder.ASC)
                .map { it.toMessageDbo() }
        }

    suspend fun getMessagesCreatedAfter(chatId: String, timestamp: UtcTimestamp): List<MessageDbo> =
        dbQuery {
            table.selectAll()
                .where {
                    (table.chatId eq chatId) and
                        (table.createdAt greater timestamp.toString()) and
                        (table.isDeleted eq false)
                }
                .orderBy(table.createdAt to SortOrder.ASC)
                .map { it.toMessageDbo() }
        }

    suspend fun getMessagesUpdatedAfter(chatId: String, timestamp: UtcTimestamp): List<MessageDbo> =
        dbQuery {
            val ts = timestamp.toString()
            table.selectAll()
                .where {
                    (table.chatId eq chatId) and
                        (table.createdAt lessEq ts) and
                        (table.lastModifiedAt greater ts) and
                        (table.isDeleted eq false)
                }
                .orderBy(table.createdAt to SortOrder.ASC)
                .map { it.toMessageDbo() }
        }

    suspend fun getDeletedMessageIdsAfter(chatId: String, timestamp: UtcTimestamp): List<String> =
        dbQuery {
            table.selectAll()
                .where {
                    (table.chatId eq chatId) and
                        (table.deletedAt greater timestamp.toString()) and
                        (table.isDeleted eq true)
                }
                .map { it[table.clientId] }
        }

    suspend fun getMessagesUpdatedInRange(
        chatId: String,
        afterTimestamp: UtcTimestamp,
        oldestTime: UtcTimestamp,
        newestTime: UtcTimestamp,
    ): List<MessageDbo> = dbQuery {
        table.selectAll()
            .where {
                (table.chatId eq chatId) and
                    (table.createdAt greaterEq oldestTime.toString()) and
                    (table.createdAt lessEq newestTime.toString()) and
                    (table.lastModifiedAt greater afterTimestamp.toString()) and
                    (table.isDeleted eq false)
            }
            .orderBy(table.createdAt to SortOrder.ASC)
            .map { it.toMessageDbo() }
    }

    suspend fun getDeletedMessageIdsInRange(
        chatId: String,
        afterTimestamp: UtcTimestamp,
        oldestTime: UtcTimestamp,
        newestTime: UtcTimestamp,
    ): List<String> = dbQuery {
        table.selectAll()
            .where {
                (table.chatId eq chatId) and
                    (table.createdAt greaterEq oldestTime.toString()) and
                    (table.createdAt lessEq newestTime.toString()) and
                    (table.deletedAt greater afterTimestamp.toString()) and
                    (table.isDeleted eq true)
            }
            .map { it[table.clientId] }
    }

    suspend fun hasUpdatesBeforeTime(
        chatId: String,
        afterTimestamp: UtcTimestamp,
        beforeTime: UtcTimestamp,
    ): Boolean = dbQuery {
        table.selectAll()
            .where {
                (table.chatId eq chatId) and
                    (table.createdAt less beforeTime.toString()) and
                    (
                        (table.lastModifiedAt greater afterTimestamp.toString()) or
                            (
                                (table.isDeleted eq true) and
                                    (table.deletedAt greater afterTimestamp.toString())
                                )
                        )
            }
            .limit(1)
            .any()
    }

    suspend fun hasMessagesAfterTime(chatId: String, afterTime: UtcTimestamp): Boolean = dbQuery {
        table.selectAll()
            .where {
                (table.chatId eq chatId) and
                    (table.createdAt greater afterTime.toString()) and
                    (table.isDeleted eq false)
            }
            .limit(1)
            .any()
    }

    suspend fun hasChangesAfter(chatId: String, timestamp: UtcTimestamp): Boolean = dbQuery {
        val ts = timestamp.toString()
        table.selectAll()
            .where {
                (table.chatId eq chatId) and
                    (
                        (table.createdAt greater ts) or
                            (table.lastModifiedAt greater ts) or
                            ((table.isDeleted eq true) and (table.deletedAt greater ts))
                        )
            }
            .limit(1)
            .any()
    }

    suspend fun getAllMessagesByChatId(
        chatId: String,
        descending: Boolean = true,
    ): List<MessageDbo> = dbQuery {
        table.selectAll()
            .where { table.chatId eq chatId }
            .orderBy(table.createdAt to if (descending) SortOrder.DESC else SortOrder.ASC)
            .map { it.toMessageDbo() }
    }

    suspend fun getMessagesByClientIds(clientIds: List<String>): List<MessageDbo> {
        if (clientIds.isEmpty()) return emptyList()
        return dbQuery {
            table.selectAll().where { table.clientId inList clientIds }.map { it.toMessageDbo() }
        }
    }

    suspend fun updateMessage(
        messageId: String,
        imageUrl: String? = null,
        isRead: Boolean? = null,
        status: String? = null,
        text: String? = null,
        nsfw: Boolean? = null,
        failReason: String? = null,
        imageDebugInfo: String? = null,
    ) {
        if (imageUrl == null && isRead == null && status == null && text == null &&
            nsfw == null && imageDebugInfo == null
        ) return

        val before = getMessageById(messageId) ?: return
        dbQuery {
            table.update({ table.id eq messageId }) { statement ->
                imageUrl?.let { statement[table.imageUrl] = it }
                imageDebugInfo?.let { statement[table.imageDebugInfo] = it }
                isRead?.let { statement[table.isRead] = it }
                status?.let {
                    statement[table.status] = it
                    // Причина живёт только вместе со статусом FAILED
                    statement[table.failReason] =
                        if (it == MessageStatus.FAILED.value) failReason else null
                }
                text?.let { statement[table.text] = it }
                nsfw?.let { statement[table.nsfw] = it }
                statement[table.lastModifiedAt] = UtcTimestamp.now().toString()
            }
        }
        // Событие — после коммита: на нём висит SSE-стрим ответа персонажа
        getMessageById(messageId)?.let { events.updated(before, it) }
    }

    suspend fun failStuckStreamingMessages(stuckSince: UtcTimestamp): Long {
        val stuck = dbQuery {
            table.selectAll()
                .where {
                    (table.status eq MessageStatus.STREAMING.value) and
                        (table.lastModifiedAt less stuckSince.toString())
                }
                .map { it.toMessageDbo() }
        }
        if (stuck.isEmpty()) return 0
        val now = UtcTimestamp.now().toString()
        dbQuery {
            table.update({ table.id inList stuck.map { it.id } }) {
                it[table.status] = MessageStatus.FAILED.value
                it[table.text] = ""
                it[table.failReason] = com.lvsmsmch.aichat.chat.network.FailReason.ERROR
                it[table.lastModifiedAt] = now
            }
        }
        // Клиент, висящий на стриме зависшего сообщения, должен узнать о провале
        stuck.forEach { old ->
            events.updated(
                old,
                old.copy(
                    status = MessageStatus.FAILED.value,
                    text = "",
                    failReason = com.lvsmsmch.aichat.chat.network.FailReason.ERROR,
                    lastModifiedAt = now,
                ),
            )
        }
        return stuck.size.toLong()
    }

    suspend fun markMessagesAsRead(messageIds: List<String>): Int {
        if (messageIds.isEmpty()) return 0
        return dbQuery {
            table.update({ (table.id inList messageIds) and (table.isRead eq false) }) {
                it[table.isRead] = true
                it[table.lastModifiedAt] = UtcTimestamp.now().toString()
            }
        }
    }

    /** Удаление сообщений всегда мягкое: клиент узнаёт о нём синхронизацией. */
    private suspend fun softDelete(where: () -> Op<Boolean>) {
        val now = UtcTimestamp.now().toString()
        dbQuery {
            table.update({ where() }) {
                it[table.isDeleted] = true
                it[table.deletedAt] = now
                it[table.lastModifiedAt] = now
            }
        }
    }

    suspend fun deleteMessagesByIds(chatId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        softDelete({ (table.id inList messageIds) and (table.chatId eq chatId) })
    }

    suspend fun deleteMessagesByIds(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        softDelete({ table.id inList messageIds })
    }

    /** Редактирование сообщения: вся история ПОСЛЕ него удаляется (разговор продолжается заново). */
    suspend fun deleteMessagesCreatedAfter(chatId: String, timestamp: String) {
        softDelete({ (table.chatId eq chatId) and (table.createdAt greater timestamp) })
    }

    /** Все senderId сообщений чата — включая удалённых из группы участников. */
    suspend fun getDistinctSenderIds(chatId: String): List<String> = dbQuery {
        table.select(table.senderId)
            .where { table.chatId eq chatId }
            .withDistinct()
            .map { it[table.senderId] }
    }

    /** Soft-delete сообщений сразу многих чатов (каскад удаления аккаунта). */
    suspend fun deleteAllMessagesInChats(session: DbSession, chatIds: List<String>) {
        if (chatIds.isEmpty()) return
        softDelete({ table.chatId inList chatIds })
    }

    suspend fun deleteAllMessagesInChat(chatId: String) {
        softDelete({ table.chatId eq chatId })
    }
}
