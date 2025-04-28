package com.lvsmsmch.aichat.db.repositories.content

import com.lvsmsmch.aichat.db.repositories._utils.ChangeEvent
import com.lvsmsmch.aichat.db.repositories._utils.watchAsFlow
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.conversions.Bson
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
    val characterIsPublic: Boolean,
    val isChatMuted: Boolean = false,
    val lastMessage: MessageDbo? = null,
    val unreadMessageCount: Int = 0
)

class ChatRepository(
    private val collection: CoroutineCollection<ChatDbo>,
    private val onMessageAddedForCharacter: (characterId: String) -> Unit,
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
        }
    }


    /**
     * FLOW
     */

    private val changeEventsFlow: SharedFlow<ChangeEvent<ChatDbo>> by lazy {
        collection
            .watchAsFlow()
            .shareIn(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                started = SharingStarted.Eagerly,
                replay = 0
            )
    }

    fun collectAllEventsForChatId(vararg chatIds: String): Flow<ChangeEvent<ChatDbo>> {
        if (chatIds.isEmpty()) return emptyFlow()
        val chatIdSet = chatIds.toSet()
        return changeEventsFlow.filter { it.latestObject.id in chatIdSet }
    }

    fun collectAllEventsForUserId(userId: String): Flow<ChangeEvent<ChatDbo>> {
        return changeEventsFlow.filter { it.latestObject.userId == userId }
    }

    fun collectDeletionEventsForChat(vararg chatIds: String): Flow<ChangeEvent.Deleted<ChatDbo>> {
        return collectAllEventsForChatId(*chatIds)
            .filterIsInstance<ChangeEvent.Deleted<ChatDbo>>()
    }


    /**
     * CREATE
     */
    suspend fun createChat(
        userId: String,
        characterId: String,
        characterName: String,
        characterDescription: String,
        characterPrompt: String,
        characterPicUrl: String,
        characterIsPublic: Boolean
    ): ChatDbo {
        val chat = ChatDbo(
            userId = userId,
            characterId = characterId,
            characterName = characterName,
            characterDescription = characterDescription,
            characterPrompt = characterPrompt,
            characterPicUrl = characterPicUrl,
            characterIsPublic = characterIsPublic,
        )
        collection.insertOne(chat)
        return chat
    }


    /**
     * READ
     */
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

    suspend fun findChatByUserAndCharacter(userId: String, characterId: String): ChatDbo? {
        return collection.findOne(
            and(
                ChatDbo::userId eq userId,
                ChatDbo::characterId eq characterId
            )
        )
    }

    suspend fun countUnreadChatsByUserId(userId: String): Int {
        return collection.countDocuments(
            and(
                ChatDbo::userId eq userId,
                // todo count only that chats in which last message is not read
            )
        ).toInt()
    }

    suspend fun countChatsByUserId(userId: String): Int {
        return collection.countDocuments(ChatDbo::userId eq userId).toInt()
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
        collection.updateOneById(chatId, combine(*updates.toTypedArray()))
    }


    /**
     * DELETE
     */
    suspend fun deleteChat(chatId: String) {
        val chatDbo = collection.findOneById(chatId) ?: return
        collection.deleteOneById(chatId)
    }


    /**
     * OTHER
     */

    suspend fun onMessageAddedForChat(chatId: String) {
        val characterId = collection.findOneById(chatId)?.characterId ?: return
        onMessageAddedForCharacter(characterId)
    }

    suspend fun onNewLastMessage(chatId: String, message: MessageDbo?) {
        collection.updateOneById(chatId, setValue(ChatDbo::lastMessage, message))
    }

    suspend fun onNewUnreadCount(chatId: String, unreadCount: Int) {
        collection.updateOneById(chatId, setValue(ChatDbo::unreadMessageCount, unreadCount))
    }

    suspend fun onCharacterChanged(characterDbo: CharacterDbo) {
        val updates = mutableListOf<Bson>()

        updates.add(setValue(ChatDbo::characterName, characterDbo.name))
        updates.add(setValue(ChatDbo::characterDescription, characterDbo.description))
        updates.add(setValue(ChatDbo::characterPrompt, characterDbo.prompt))
        updates.add(setValue(ChatDbo::characterPicUrl, characterDbo.picUrl))
        updates.add(setValue(ChatDbo::characterIsPublic, characterDbo.isPublic))

        collection.updateMany(
            ChatDbo::characterId eq characterDbo.id,
            combine(*updates.toTypedArray())
        )

        if (!characterDbo.isPublic) {
            // remove all chats created by not character author
            collection.deleteMany(
                and(
                    ChatDbo::characterId eq characterDbo.id,
                    ChatDbo::userId ne characterDbo.authorId
                )
            )
        }

    }
}