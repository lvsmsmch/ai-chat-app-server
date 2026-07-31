package com.lvsmsmch.aichat.cache.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.mongodb.client.model.ReplaceOptions
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.coroutine.CoroutineCollection

/**
 * Секции вкладки For you (Discover): готовые списки id персонажей на юзера.
 * Главные секции (for_you / trending / most_popular) — по 30 id (клиент
 * пагинирует по 10), категорийные — по 10. Документ "__default" — набор для
 * свежерегнутых юзеров: копируется им мгновенно, без расчёта.
 */
data class DiscoverSectionDbo(
    val key: String,
    val characterIds: List<String>,
)

data class DiscoverSectionsCacheDbo(
    @BsonId val id: String, // userId или "__default"
    val sections: List<DiscoverSectionDbo> = emptyList(),
    val updatedAt: String = UtcTimestamp.now().toString(),
)

const val DEFAULT_DISCOVER_CACHE_ID = "__default"

class DiscoverSectionsCacheRepository(
    private val collection: CoroutineCollection<DiscoverSectionsCacheDbo>,
) {
    suspend fun upsert(id: String, sections: List<DiscoverSectionDbo>) {
        collection.replaceOneById(
            id,
            DiscoverSectionsCacheDbo(id = id, sections = sections),
            ReplaceOptions().upsert(true),
        )
    }

    suspend fun get(id: String): DiscoverSectionsCacheDbo? = collection.findOneById(id)

    /** Кэш юзера, а если его ещё нет — дефолтный набор новорега. */
    suspend fun getForUserOrDefault(userId: String): DiscoverSectionsCacheDbo? =
        get(userId) ?: get(DEFAULT_DISCOVER_CACHE_ID)

    /** Мгновенный кэш для свежерегнутого юзера — копия дефолтного набора. */
    suspend fun copyDefaultTo(userId: String) {
        val default = get(DEFAULT_DISCOVER_CACHE_ID) ?: return
        upsert(userId, default.sections)
    }

    suspend fun delete(id: String) {
        collection.deleteOneById(id)
    }
}
