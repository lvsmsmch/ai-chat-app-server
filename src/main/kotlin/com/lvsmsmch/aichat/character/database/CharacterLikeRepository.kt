package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.mongodb.reactivestreams.client.ClientSession
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

/** Лайк персонажа: id = "<userId>_<characterId>" (естественная уникальность). */
@Serializable
data class CharacterLikeDbo(
    @BsonId val id: String,
    val userId: String,
    val characterId: String,
    val likedAt: String = UtcTimestamp.now().toString(),
)

class CharacterLikeRepository(
    private val collection: CoroutineCollection<CharacterLikeDbo>,
) {
    suspend fun ensureIndexes() {
        collection.ensureIndex(ascending(CharacterLikeDbo::userId))
        collection.ensureIndex(ascending(CharacterLikeDbo::characterId))
        // Лента «Liked» юзера: свежие сверху
        collection.ensureIndex(
            com.mongodb.client.model.Indexes.compoundIndex(
                ascending(CharacterLikeDbo::userId),
                descending(CharacterLikeDbo::likedAt),
            )
        )
    }

    private fun likeId(userId: String, characterId: String) = "${userId}_${characterId}"

    /** @return true, если лайк реально добавлен (а не повтор). */
    suspend fun like(userId: String, characterId: String): Boolean {
        val id = likeId(userId, characterId)
        if (collection.findOneById(id) != null) return false
        collection.insertOne(CharacterLikeDbo(id = id, userId = userId, characterId = characterId))
        return true
    }

    /** @return true, если лайк реально снят. */
    suspend fun unlike(userId: String, characterId: String): Boolean =
        collection.deleteOneById(likeId(userId, characterId)).deletedCount > 0

    suspend fun isLiked(userId: String, characterId: String): Boolean =
        collection.findOneById(likeId(userId, characterId)) != null

    /** Батч-проверка для списков: какие из [characterIds] лайкнуты юзером. */
    suspend fun getLikedIds(userId: String, characterIds: List<String>): Set<String> {
        if (characterIds.isEmpty()) return emptySet()
        return collection.find(
            CharacterLikeDbo::id `in` characterIds.map { likeId(userId, it) }
        ).toList().map { it.characterId }.toSet()
    }

    /** Лайкнутые персонажи юзера, свежие сверху; курсор — likedAt. */
    suspend fun getLikedCharacterIds(
        userId: String,
        cursor: String?,
        size: Int,
    ): Pair<List<String>, String?> {
        val filters = listOfNotNull(
            CharacterLikeDbo::userId eq userId,
            cursor?.let { CharacterLikeDbo::likedAt lt it },
        )
        val items = collection.find(and(filters))
            .sort(descending(CharacterLikeDbo::likedAt))
            .limit(size + 1)
            .toList()
        val page = items.take(size)
        val next = if (items.size > size) page.lastOrNull()?.likedAt else null
        return page.map { it.characterId } to next
    }

    suspend fun countForCharacter(characterId: String): Int =
        collection.countDocuments(CharacterLikeDbo::characterId eq characterId).toInt()

    suspend fun removeAllForCharacters(session: ClientSession, characterIds: List<String>) {
        if (characterIds.isEmpty()) return
        collection.deleteMany(session, CharacterLikeDbo::characterId `in` characterIds)
    }

    /** Лайки, поставленные юзером (при удалении аккаунта). */
    suspend fun getLikesByUser(session: ClientSession, userId: String): List<CharacterLikeDbo> =
        collection.find(CharacterLikeDbo::userId eq userId).toList()

    suspend fun removeAllByUser(session: ClientSession, userId: String) {
        collection.deleteMany(session, CharacterLikeDbo::userId eq userId)
    }
}
