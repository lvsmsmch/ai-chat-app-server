package com.lvsmsmch.aichat.chat.database

import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.character.database.CharacterVisibility
import com.lvsmsmch.aichat.utils.DatabaseEvent
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
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
enum class ChatType(val code: String) {
    DIRECT("direct"),
    GROUP("group")       /* For future. */
}

@Serializable
data class ChatDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val clientId: String = UUID.randomUUID().toString(),
    val lastModifiedAt: UtcTimestamp = UtcTimestamp.now(),
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val chatType: ChatType = ChatType.DIRECT,
    val userId: String,
    val characterIds: List<String> = listOf(),
    val isChatMuted: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: UtcTimestamp = UtcTimestamp.now(),
)

class ChatRepository(
    private val collection: CoroutineCollection<ChatDbo>
) {

    /**
     * Initialize indexes for the collection
     */

    init {
        initializeIndexes()
    }

    private fun initializeIndexes() {
        runBlocking {
            collection.ensureIndex(ascending(ChatDbo::userId))
//            collection.ensureIndex(ascending("${ChatDbo::characters.name}.${CharacterInfo::id.name}"))
        }
    }


    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    fun collectAllEventsForUserId(userId: String): Flow<DatabaseEvent<ChatDbo>> {
        return databaseEventsFlow.filter { it.latestObject.userId == userId }
    }

    /**
     * CREATE
     */

    suspend fun insertChat(chatDbo: ChatDbo) {
        collection.insertOne(chatDbo)
    }

    /**
     * READ
     */

    suspend fun getChatsCreatedAfter(userId: String, timestamp: UtcTimestamp): List<ChatDbo> {
        return collection.find(
            and(
                ChatDbo::userId eq userId,
                ChatDbo::createdAt gt timestamp,
                ChatDbo::isDeleted eq false
            )
        ).toList()
    }

    // Получить чаты, обновленные (но не созданные) после timestamp
    suspend fun getChatsUpdatedAfter(userId: String, timestamp: UtcTimestamp): List<ChatDbo> {
        return collection.find(
            and(
                ChatDbo::userId eq userId,
                ChatDbo::createdAt lte timestamp,
                ChatDbo::lastModifiedAt gt timestamp,
                ChatDbo::isDeleted eq false
            )
        ).toList()
    }

    // Получить ID чатов, удаленных после timestamp
    suspend fun getDeletedChatIdsAfter(userId: String, timestamp: UtcTimestamp): List<String> {
        return collection.find(
            and(
                ChatDbo::userId eq userId,
                ChatDbo::deletedAt gt timestamp,
                ChatDbo::isDeleted eq true
            )
        ).toList().map { it.id }
    }

    suspend fun getChatById(chatId: String): ChatDbo? {
        return collection.findOneById(chatId)
    }

    suspend fun getChatsByUserId(userId: String): List<ChatDbo> {
        return collection.find(ChatDbo::userId eq userId).toList()
    }

    suspend fun getChatsByIds(chatIds: List<String>): List<ChatDbo> {
        if (chatIds.isEmpty()) {
            return emptyList()
        }

        return collection.find(ChatDbo::id.`in`(chatIds)).toList()
    }

    suspend fun getAllNonDeletedChats(): List<ChatDbo> {
        return collection.find(ChatDbo::isDeleted eq false).toList()
    }

    suspend fun findChatByUserAndCharacter(
        userId: String,
        characterId: String,
        includeDeleted: Boolean = false
    ): ChatDbo? {
        return collection.findOne(
            and(
                ChatDbo::userId eq userId,
                if (includeDeleted) EMPTY_BSON else ChatDbo::isDeleted eq false,
                ChatDbo::chatType eq ChatType.DIRECT,
                ChatDbo::characterIds eq listOf(characterId)
            )
        )
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
     * UPDATE
     */
    suspend fun updateChat(
        chatId: String,
        isChatMuted: Boolean? = null,
    ) {
        collection.findOneById(chatId) ?: return
        val updates = mutableListOf<Bson>()
        isChatMuted?.let { updates.add(setValue(ChatDbo::isChatMuted, it)) }
        if (updates.isEmpty()) return // Nothing to update
        collection.updateOneById(
            chatId,
            combine(
                *updates.toTypedArray(),
                setValue(ChatDbo::lastModifiedAt, UtcTimestamp.now())
            )
        )
    }

    suspend fun updateChatsAfterCharacterVisibilityWasChanged(characterDbo: CharacterDbo) {
        if (characterDbo.visibility == CharacterVisibility.PRIVATE.code) {
            collection.updateMany(
                and(
                    ChatDbo::characterIds contains characterDbo.id,
                    ChatDbo::userId ne characterDbo.authorId,
                    ChatDbo::isDeleted eq false
                ),
                combine(
                    setValue(ChatDbo::isDeleted, true),
                    setValue(ChatDbo::deletedAt, UtcTimestamp.now()),
                    setValue(ChatDbo::lastModifiedAt, UtcTimestamp.now())
                )
            )
        }
    }

//    suspend fun updateLastMessageInChat(
//        chatId: String,
//        message: MessageDbo?
//    ) {
//        collection.updateOneById(
//            chatId,
//            combine(
//                setValue(ChatDbo::lastMessage, message),
//                setValue(ChatDbo::updatedAt, UtcTimestamp.now())
//            )
//        )
//    }
//
//    suspend fun updateUnreadMessagesCountInChat(
//        chatId: String,
//        unreadMessageCount: Int
//    ) {
//        collection.updateOneById(
//            chatId,
//            combine(
//                setValue(ChatDbo::unreadMessageCount, unreadMessageCount),
//                setValue(ChatDbo::updatedAt, UtcTimestamp.now())
//            )
//        )
//    }

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
                setValue(ChatDbo::deletedAt, UtcTimestamp.now()),
                setValue(ChatDbo::lastModifiedAt, UtcTimestamp.now())
            )
        )
    }

    suspend fun deleteAllChatsByCharacterId(characterId: String) {
        collection.updateMany(
            and(
                ChatDbo::characterIds contains characterId,
                ChatDbo::isDeleted eq false
            ),
            combine(
                setValue(ChatDbo::isDeleted, true),
                setValue(ChatDbo::deletedAt, UtcTimestamp.now()),
                setValue(ChatDbo::lastModifiedAt, UtcTimestamp.now())
            )
        )
    }
}