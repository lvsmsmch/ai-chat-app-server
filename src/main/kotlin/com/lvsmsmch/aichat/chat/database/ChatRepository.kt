package com.lvsmsmch.aichat.chat.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.encodeStringList
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toChatDbo
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class ChatRepository {

    private val table = Tables.Chats

    /**
     * Участники чата лежат JSON-массивом, поэтому «содержит персонажа» — это
     * поиск закавыченного id в тексте. Ложных совпадений быть не может: id
     * буквенно-цифровые и в JSON всегда в кавычках.
     */
    private fun containsCharacter(characterId: String): Op<Boolean> =
        table.characterIds like "%\"$characterId\"%"

    private fun containsAnyCharacter(characterIds: List<String>): Op<Boolean> =
        characterIds
            .map { containsCharacter(it) }
            .reduce { acc, op -> acc or op }

    suspend fun insertChat(session: DbSession, chatDbo: ChatDbo) {
        dbQuery { table.insert { it.from(chatDbo) } }
    }

    suspend fun getChatById(session: DbSession, chatId: String): ChatDbo? = getChatById(chatId)

    suspend fun getChatById(chatId: String): ChatDbo? = dbQuery {
        table.selectAll().where { table.id eq chatId }.limit(1).firstOrNull()?.toChatDbo()
    }

    suspend fun getChatByClientId(clientId: String): ChatDbo? = dbQuery {
        table.selectAll().where { table.clientId eq clientId }.limit(1).firstOrNull()?.toChatDbo()
    }

    suspend fun getChatsByClientIds(clientIds: List<String>): List<ChatDbo> {
        if (clientIds.isEmpty()) return emptyList()
        return dbQuery {
            table.selectAll().where { table.clientId inList clientIds }.map { it.toChatDbo() }
        }
    }

    suspend fun getChatsByUserId(userId: String): List<ChatDbo> = dbQuery {
        table.selectAll()
            .where { (table.userId eq userId) and (table.isDeleted eq false) }
            .orderBy(table.lastModifiedAt to SortOrder.DESC)
            .map { it.toChatDbo() }
    }

    suspend fun getChatsByUserIdAfter(userId: String, timestamp: UtcTimestamp): List<ChatDbo> =
        dbQuery {
            val ts = timestamp.toString()
            table.selectAll()
                .where {
                    (table.userId eq userId) and (
                        ((table.createdAt greater ts) and (table.isDeleted eq false)) or
                            ((table.lastModifiedAt greater ts) and (table.isDeleted eq false)) or
                            ((table.deletedAt greater ts) and (table.isDeleted eq true))
                        )
                }
                .orderBy(table.lastModifiedAt to SortOrder.DESC)
                .map { it.toChatDbo() }
        }

    suspend fun getChatsCreatedAfter(userId: String, timestamp: UtcTimestamp): List<ChatDbo> =
        dbQuery {
            table.selectAll()
                .where {
                    (table.userId eq userId) and
                        (table.createdAt greater timestamp.toString()) and
                        (table.isDeleted eq false)
                }
                .map { it.toChatDbo() }
        }

    suspend fun getChatsUpdatedAfter(userId: String, timestamp: UtcTimestamp): List<ChatDbo> =
        dbQuery {
            val ts = timestamp.toString()
            table.selectAll()
                .where {
                    (table.userId eq userId) and
                        (table.createdAt lessEq ts) and
                        (table.lastModifiedAt greater ts) and
                        (table.isDeleted eq false)
                }
                .map { it.toChatDbo() }
        }

    suspend fun getDeletedChatIdsAfter(userId: String, timestamp: UtcTimestamp): List<String> =
        dbQuery {
            table.selectAll()
                .where {
                    (table.userId eq userId) and
                        (table.deletedAt greater timestamp.toString()) and
                        (table.isDeleted eq true)
                }
                .map { it[table.clientId] }
        }

    suspend fun findChatByUserAndCharacter(
        userId: String,
        characterId: String,
        includeDeleted: Boolean = false,
    ): ChatDbo? = dbQuery {
        table.selectAll()
            .where {
                var op: Op<Boolean> = (table.userId eq userId) and
                    (table.type eq ChatType.DIRECT.name) and
                    // Тет-а-тет: состав ровно из одного персонажа
                    (table.characterIds eq encodeStringList(listOf(characterId)))
                if (!includeDeleted) op = op and (table.isDeleted eq false)
                op
            }
            .limit(1)
            .firstOrNull()
            ?.toChatDbo()
    }

    suspend fun findGroupChatByUserAndCharacters(
        userId: String,
        characterIds: List<String>,
    ): ChatDbo? {
        val sortedCharacterIds = characterIds.sorted()
        val groupChats = dbQuery {
            table.selectAll()
                .where {
                    (table.userId eq userId) and
                        (table.type eq ChatType.GROUP.name) and
                        (table.isDeleted eq false)
                }
                .map { it.toChatDbo() }
        }
        // Сравниваем состав как множество: порядок участников значения не имеет
        return groupChats.find { chat -> chat.characterIds.sorted() == sortedCharacterIds }
    }

    suspend fun doAllChatsBelongToUser(chatIds: List<String>, userId: String): Boolean {
        if (chatIds.isEmpty()) return true
        val matching = dbQuery {
            table.selectAll()
                .where { (table.id inList chatIds) and (table.userId eq userId) }
                .count()
                .toInt()
        }
        return matching == chatIds.size
    }

    suspend fun getChatsByIds(chatIds: List<String>): List<ChatDbo> {
        if (chatIds.isEmpty()) return emptyList()
        return dbQuery {
            table.selectAll().where { table.id inList chatIds }.map { it.toChatDbo() }
        }
    }

    suspend fun getChatsByCharacterIds(
        session: DbSession,
        characterIds: List<String>,
    ): List<ChatDbo> {
        if (characterIds.isEmpty()) return emptyList()
        return dbQuery {
            table.selectAll()
                .where { containsAnyCharacter(characterIds) and (table.isDeleted eq false) }
                .map { it.toChatDbo() }
        }
    }

    suspend fun getChatsByCharacterId(session: DbSession, characterId: String): List<ChatDbo> =
        dbQuery {
            table.selectAll()
                .where { containsCharacter(characterId) and (table.isDeleted eq false) }
                .map { it.toChatDbo() }
        }

    suspend fun getAllNonDeletedChats(): List<ChatDbo> = dbQuery {
        table.selectAll().where { table.isDeleted eq false }.map { it.toChatDbo() }
    }

    suspend fun updateChat(
        chatId: String,
        isMuted: Boolean? = null,
        customName: String? = null,
        characterIds: List<String>? = null,
        /** Пустая строка — сброс к обложке персонажа, null — не менять. */
        cover: String? = null,
    ) {
        if (isMuted == null && customName == null && characterIds == null && cover == null) return
        dbQuery {
            table.update({ table.id eq chatId }) { statement ->
                isMuted?.let { statement[table.isMuted] = it }
                customName?.let { statement[table.customName] = it }
                characterIds?.let { statement[table.characterIds] = encodeStringList(it) }
                cover?.let { statement[table.cover] = it.takeIf { c -> c.isNotBlank() } }
                statement[table.lastModifiedAt] = UtcTimestamp.now().toString()
            }
        }
    }

    suspend fun deleteChat(chatId: String) {
        deleteChatsByIds(listOf(chatId))
    }

    /** Удаление всегда мягкое: клиенты узнают об этом синхронизацией по deletedAt. */
    private suspend fun softDelete(where: () -> Op<Boolean>, touchLastModified: Boolean = true) {
        val now = UtcTimestamp.now().toString()
        dbQuery {
            table.update({ where() }) {
                it[table.isDeleted] = true
                it[table.deletedAt] = now
                if (touchLastModified) it[table.lastModifiedAt] = now
            }
        }
    }

    suspend fun deleteChatsByIds(chatIds: List<String>) {
        if (chatIds.isEmpty()) return
        softDelete({ table.id inList chatIds })
    }

    suspend fun deleteAllChatsByCharacterId(session: DbSession, characterId: String) {
        softDelete({ containsCharacter(characterId) and (table.isDeleted eq false) })
    }

    /** Все чаты юзера (для каскада удаления аккаунта). */
    /** Тот же разовый перевод обложек на номера, что и у персонажей. */
    suspend fun migrateLegacyCovers(): Int = dbQuery {
        var changed = 0
        com.lvsmsmch.aichat.character.ChatCovers.legacyIds.forEach { (old, id) ->
            changed += table.update({ table.cover eq old }) { it[table.cover] = id }
        }
        changed
    }

    suspend fun getChatIdsByUserId(session: DbSession, userId: String): List<String> = dbQuery {
        table.selectAll().where { table.userId eq userId }.map { it[table.id] }
    }

    /** Soft-delete всех чатов юзера; возвращает их id для каскада сообщений. */
    suspend fun deleteAllChatsByUserId(session: DbSession, userId: String): List<String> {
        val ids = getChatIdsByUserId(session, userId)
        if (ids.isEmpty()) return ids
        // lastModifiedAt здесь НЕ трогаем — как было в Mongo-версии
        softDelete({ table.id inList ids }, touchLastModified = false)
        return ids
    }

    suspend fun deleteAllChatsByCharacterIds(session: DbSession, characterIds: List<String>) {
        if (characterIds.isEmpty()) return
        softDelete({ containsAnyCharacter(characterIds) and (table.isDeleted eq false) })
    }

    suspend fun deleteChatsForWhoIsNotAuthor(
        session: DbSession,
        characterId: String,
        authorId: String,
    ) {
        softDelete({
            containsCharacter(characterId) and
                (table.userId neq authorId) and
                (table.isDeleted eq false)
        })
    }
}
