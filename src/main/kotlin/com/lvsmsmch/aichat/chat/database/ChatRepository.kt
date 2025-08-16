// ============= UPDATED CHAT REPOSITORY =============

package com.lvsmsmch.aichat.chat.database

import com.lvsmsmch.aichat.utils.DatabaseEvent
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.mongodb.reactivestreams.client.ClientSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bson.conversions.Bson
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

class ChatRepository(
    private val collection: CoroutineCollection<ChatDbo>
) {

    init {
        initializeIndexes()
    }

    private fun initializeIndexes() {
        runBlocking {
            collection.ensureIndex(ascending(ChatDbo::userId))
            collection.ensureIndex(ascending(ChatDbo::clientId))
            collection.ensureIndex(
                ascending(
                    ChatDbo::userId,
                    ChatDbo::lastModifiedAt
                )
            )
            collection.ensureIndex(
                ascending(
                    ChatDbo::userId,
                    ChatDbo::isDeleted
                )
            )

            collection.ensureIndex(
                ascending(
                    ChatDbo::userId,
                    ChatDbo::type,
                    ChatDbo::characterIds
                )
            )

            collection.ensureIndex(ascending(ChatDbo::characterIds))

            collection.ensureIndex(
                ascending(
                    ChatDbo::userId,
                    ChatDbo::createdAt
                )
            )

            collection.ensureIndex(
                ascending(
                    ChatDbo::userId,
                    ChatDbo::deletedAt
                )
            )
        }
    }

    /**
     * FLOW для мониторинга изменений (если понадобится в будущем)
     */
    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    fun collectAllEventsForUserId(userId: String): Flow<DatabaseEvent<ChatDbo>> {
        return databaseEventsFlow.filter { it.latestObject.userId == userId }
    }

    /**
     * CREATE
     */
    suspend fun insertChat(session: ClientSession, chatDbo: ChatDbo) {
        collection.insertOne(session, chatDbo)
    }

    /**
     * READ - основные методы
     */
    suspend fun getChatById(session: ClientSession, chatId: String): ChatDbo? {
        return collection.findOneById(chatId, session)
    }

    suspend fun getChatById(chatId: String): ChatDbo? {
        return collection.findOneById(chatId)
    }

    suspend fun getChatByClientId(clientId: String): ChatDbo? {
        return collection.findOne(ChatDbo::clientId eq clientId)
    }

    suspend fun getChatsByClientIds(clientIds: List<String>): List<ChatDbo> {
        return collection.find(ChatDbo::clientId `in` clientIds).toList()
    }

    suspend fun getChatsByUserId(userId: String): List<ChatDbo> {
        return collection.find(
            and(
                ChatDbo::userId eq userId,
                ChatDbo::isDeleted eq false
            )
        ).sort(descending(ChatDbo::lastModifiedAt)).toList()
    }

    /**
     * READ - методы для синхронизации по времени
     */
    suspend fun getChatsByUserIdAfter(userId: String, timestamp: UtcTimestamp): List<ChatDbo> {
        return collection.find(
            and(
                ChatDbo::userId eq userId,
                or(
                    // Новые чаты
                    and(
                        ChatDbo::createdAt gt timestamp.toString(),
                        ChatDbo::isDeleted eq false
                    ),
                    // Обновленные чаты
                    and(
                        ChatDbo::lastModifiedAt gt timestamp.toString(),
                        ChatDbo::isDeleted eq false
                    ),
                    // Удаленные чаты
                    and(
                        ChatDbo::deletedAt gt timestamp.toString(),
                        ChatDbo::isDeleted eq true
                    )
                )
            )
        ).sort(descending(ChatDbo::lastModifiedAt)).toList()
    }

    suspend fun getChatsCreatedAfter(userId: String, timestamp: UtcTimestamp): List<ChatDbo> {
        return collection.find(
            and(
                ChatDbo::userId eq userId,
                ChatDbo::createdAt gt timestamp.toString(),
                ChatDbo::isDeleted eq false
            )
        ).toList()
    }

    suspend fun getChatsUpdatedAfter(userId: String, timestamp: UtcTimestamp): List<ChatDbo> {
        return collection.find(
            and(
                ChatDbo::userId eq userId,
                ChatDbo::createdAt lte timestamp.toString(),
                ChatDbo::lastModifiedAt gt timestamp.toString(),
                ChatDbo::isDeleted eq false
            )
        ).toList()
    }

    suspend fun getDeletedChatIdsAfter(userId: String, timestamp: UtcTimestamp): List<String> {
        return collection.find(
            and(
                ChatDbo::userId eq userId,
                ChatDbo::deletedAt gt timestamp.toString(),
                ChatDbo::isDeleted eq true
            )
        ).toList().map { it.clientId } // Возвращаем clientId для клиента
    }

    /**
     * READ - поиск и валидация
     */
    suspend fun findChatByUserAndCharacter(
        userId: String,
        characterId: String,
        includeDeleted: Boolean = false
    ): ChatDbo? {
        return collection.findOne(
            and(
                ChatDbo::userId eq userId,
                if (includeDeleted) EMPTY_BSON else ChatDbo::isDeleted eq false,
                ChatDbo::type eq ChatType.DIRECT,
                ChatDbo::characterIds eq listOf(characterId)
            )
        )
    }

    suspend fun findGroupChatByUserAndCharacters(
        userId: String,
        characterIds: List<String>
    ): ChatDbo? {
        // Для группового чата проверяем точное совпадение списка персонажей
        val sortedCharacterIds = characterIds.sorted()
        return collection.find(
            and(
                ChatDbo::userId eq userId,
                ChatDbo::type eq ChatType.GROUP,
                ChatDbo::isDeleted eq false
            )
        ).toList().find { chat ->
            chat.characterIds.sorted() == sortedCharacterIds
        }
    }

    suspend fun doAllChatsBelongToUser(chatIds: List<String>, userId: String): Boolean {
        if (chatIds.isEmpty()) return true

        val matchingCount = collection.countDocuments(
            and(
                ChatDbo::id.`in`(chatIds),
                ChatDbo::userId eq userId
            )
        ).toInt()

        return matchingCount == chatIds.size
    }

    /**
     * READ - вспомогательные методы
     */
    suspend fun getChatsByIds(chatIds: List<String>): List<ChatDbo> {
        if (chatIds.isEmpty()) {
            return emptyList()
        }
        return collection.find(ChatDbo::id.`in`(chatIds)).toList()
    }

    suspend fun getChatsByCharacterIds(session: ClientSession, characterIds: List<String>): List<ChatDbo> {
        if (characterIds.isEmpty()) return emptyList()

        return collection.find(
            session,
            and(
                ChatDbo::characterIds `in` characterIds,
                ChatDbo::isDeleted eq false
            )
        ).toList()
    }

    suspend fun getChatsByCharacterId(session: ClientSession, characterId: String): List<ChatDbo> {
        return collection.find(
            session,
            and(
                ChatDbo::characterIds contains characterId,
                ChatDbo::isDeleted eq false
            )
        ).toList()
    }

    suspend fun getAllNonDeletedChats(): List<ChatDbo> {
        return collection.find(ChatDbo::isDeleted eq false).toList()
    }

    /**
     * UPDATE
     */
    suspend fun updateChat(
        chatId: String,
        isMuted: Boolean? = null,
        customName: String? = null
    ) {
        collection.findOneById(chatId) ?: return
        val updates = mutableListOf<Bson>()
        isMuted?.let { updates.add(setValue(ChatDbo::isMuted, it)) }
        customName?.let { updates.add(setValue(ChatDbo::customName, it)) }
        if (updates.isEmpty()) return // Nothing to update
        collection.updateOneById(
            chatId,
            combine(
                *updates.toTypedArray(),
                setValue(ChatDbo::lastModifiedAt, UtcTimestamp.now().toString())
            )
        )
    }


    /**
     * DELETE
     */
    suspend fun deleteChat(chatId: String) {
        deleteChatsByIds(listOf(chatId))
    }

    suspend fun deleteChatsByIds(chatIds: List<String>) {
        if (chatIds.isEmpty()) return
        collection.updateMany(
            ChatDbo::id `in` chatIds,
            combine(
                setValue(ChatDbo::isDeleted, true),
                setValue(ChatDbo::deletedAt, UtcTimestamp.now().toString()),
                setValue(ChatDbo::lastModifiedAt, UtcTimestamp.now().toString())
            )
        )
    }


    suspend fun deleteAllChatsByCharacterId(session: ClientSession, characterId: String) {
        collection.updateMany(
            session,
            and(
                ChatDbo::characterIds contains characterId,
                ChatDbo::isDeleted eq false
            ),
            combine(
                setValue(ChatDbo::isDeleted, true),
                setValue(ChatDbo::deletedAt, UtcTimestamp.now().toString()),
                setValue(ChatDbo::lastModifiedAt, UtcTimestamp.now().toString())
            )
        )
    }

    suspend fun deleteAllChatsByCharacterIds(session: ClientSession, characterIds: List<String>) {
        if (characterIds.isEmpty()) return

        collection.updateMany(
            session,
            and(
                ChatDbo::characterIds `in` characterIds,
                ChatDbo::isDeleted eq false
            ),
            combine(
                setValue(ChatDbo::isDeleted, true),
                setValue(ChatDbo::deletedAt, UtcTimestamp.now().toString()),
                setValue(ChatDbo::lastModifiedAt, UtcTimestamp.now().toString())
            )
        )
    }

    suspend fun deleteChatsForWhoIsNotAuthor(session: ClientSession, characterId: String, authorId: String) {
        collection.updateMany(
            session,
            and(
                ChatDbo::characterIds contains characterId,
                ChatDbo::userId ne authorId,
                ChatDbo::isDeleted eq false
            ),
            combine(
                setValue(ChatDbo::isDeleted, true),
                setValue(ChatDbo::deletedAt, UtcTimestamp.now().toString()),
                setValue(ChatDbo::lastModifiedAt, UtcTimestamp.now().toString())
            )
        )
    }

}