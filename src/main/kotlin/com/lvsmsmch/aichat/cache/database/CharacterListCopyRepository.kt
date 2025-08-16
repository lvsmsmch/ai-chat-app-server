package com.lvsmsmch.aichat.cache.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.mongodb.client.model.ReplaceOptions
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

class CharacterListCopyRepository(private val collection: CoroutineCollection<CharacterListCopyDbo>) {

    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    // ========== CREATE / UPDATE ==========

    suspend fun upsert(characterListCopyDbo: CharacterListCopyDbo) {
        collection.replaceOneById(characterListCopyDbo.id, characterListCopyDbo, ReplaceOptions().upsert(true))
    }

    suspend fun updatePosition(userId: String, deviceId: String, listType: String, newPosition: Int) {
        val id = "${userId}_${deviceId}_${listType}"
        collection.updateOneById(
            id,
            combine(
                setValue(CharacterListCopyDbo::currentPosition, newPosition),
                setValue(CharacterListCopyDbo::lastAccessedAt, UtcTimestamp.now().toString())
            )
        )
    }

    // ========== READ ==========

    suspend fun getExistingCopy(userId: String, deviceId: String, listType: String): CharacterListCopyDbo? {
        return collection.findOneById("${userId}_${deviceId}_${listType}")
    }

    // ========== DELETE ==========

    suspend fun deleteSessionCopy(userId: String, deviceId: String, listType: String) {
        val id = "${userId}_${deviceId}_${listType}"
        collection.deleteOneById(id)
    }

    suspend fun deleteAllUserCopies(userId: String) {
        collection.deleteMany(CharacterListCopyDbo::userId eq userId)
    }

    suspend fun deleteAllSearchCopiesForUserDevice(userId: String, deviceId: String) {
        collection.deleteMany(
            and(
                CharacterListCopyDbo::userId eq userId,
                CharacterListCopyDbo::deviceId eq deviceId,
                CharacterListCopyDbo::listType.regex("^search_.*")
            )
        )
    }

    suspend fun deleteOldCopies(): Long {
        val dayAgo = UtcTimestamp.now().subtractHours(24)
        val result = collection.deleteMany(
            CharacterListCopyDbo::lastAccessedAt lt dayAgo.toString()
        )
        return result.deletedCount
    }

    // ========== UTILITY ==========

    suspend fun getCopyStats(): SessionCopyStats {
        val totalCopies = collection.countDocuments()
        val now = UtcTimestamp.now()

        val activeCopies = collection.countDocuments(
            CharacterListCopyDbo::lastAccessedAt gte now.subtractHours(1).toString()
        )

        val oldCopies = collection.countDocuments(
            CharacterListCopyDbo::lastAccessedAt lt now.subtractHours(24).toString()
        )

        val searchCopies = collection.countDocuments(
            CharacterListCopyDbo::listType.regex("^search_.*")
        )

        val categoryCopies = collection.countDocuments(
            CharacterListCopyDbo::listType.regex("^category_.*")
        )

        val personalizedCopies = collection.countDocuments(
            CharacterListCopyDbo::listType eq "personalized"
        )

        return SessionCopyStats(
            totalCopies = totalCopies.toInt(),
            activeCopies = activeCopies.toInt(),
            oldCopies = oldCopies.toInt(),
            searchCopies = searchCopies.toInt(),
            categoryCopies = categoryCopies.toInt(),
            personalizedCopies = personalizedCopies.toInt()
        )
    }

    // ========== DATA CLASSES ==========

    data class SessionCopyResult(
        val characterIds: List<String>,
        val nextPosition: Int,
        val hasMore: Boolean,
        val totalCount: Int
    )

    data class SessionCopyStats(
        val totalCopies: Int,
        val activeCopies: Int,
        val oldCopies: Int,
        val searchCopies: Int,
        val categoryCopies: Int,
        val personalizedCopies: Int
    )
}