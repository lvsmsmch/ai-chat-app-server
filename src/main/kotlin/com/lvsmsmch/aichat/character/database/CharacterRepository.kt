package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.utils.*
import com.mongodb.client.model.Filters.regex
import com.mongodb.reactivestreams.client.ClientSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection


class CharacterRepository(
    private val collection: CoroutineCollection<CharacterDbo>
) {

    /**
     * Initialize indexes for the collection
     */

    init {
        initializeIndexes()
    }


    private fun initializeIndexes() {
        runBlocking {
            collection.ensureIndex(ascending(CharacterDbo::authorId))
            collection.ensureIndex(ascending(CharacterDbo::visibility))

            collection.ensureIndex(
                ascending(
                    CharacterDbo::visibility,
                    CharacterDbo::category
                )
            )

            collection.ensureIndex(descending(CharacterDbo::createdAt))
            collection.ensureIndex(descending(CharacterDbo::averageRating))
            collection.ensureIndex(descending(CharacterDbo::totalMessages))
            collection.ensureIndex(descending(CharacterDbo::trendingScore))
            collection.ensureIndex(descending(CharacterDbo::recommendationScore))

            collection.ensureIndex(ascending(CharacterDbo::name))
            collection.ensureIndex(ascending(CharacterDbo::description))

            collection.ensureIndex(
                ascending(
                    CharacterDbo::authorId,
                    CharacterDbo::visibility
                )
            )

            collection.ensureIndex(
                ascending(
                    CharacterDbo::visibility,
                    CharacterDbo::recommendationScore
                )
            )
        }
    }

    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    /**
     * CREATE
     */

    suspend fun addCharacter(session: ClientSession, character: CharacterDbo) {
        collection.insertOne(session, character)
    }

    /**
     * READ
     */
    suspend fun getCharacters(
        searchQuery: String = "",
        sortCriteria: Int? = null,
        categories: List<CharacterCategory> = CharacterCategory.entries.toList(),
        page: Int,
        size: Int,
        authorId: String? = null,
        visibilityFilter: Int? = null,
    ): List<CharacterDbo> {
        val sortCriteriaBson = when (sortCriteria) {
            CharacterSortCriteria.NEWEST.code -> descending(CharacterDbo::createdAt)
            CharacterSortCriteria.OLDEST.code -> ascending(CharacterDbo::createdAt)
            CharacterSortCriteria.HIGHEST_RATING.code -> descending(CharacterDbo::averageRating)
            CharacterSortCriteria.LOWEST_RATING.code -> ascending(CharacterDbo::averageRating)
            CharacterSortCriteria.MOST_POPULAR.code -> descending(CharacterDbo::totalMessages)
            CharacterSortCriteria.LEAST_POPULAR.code -> ascending(CharacterDbo::totalMessages)
            CharacterSortCriteria.TRENDING.code -> descending(CharacterDbo::trendingScore)
            CharacterSortCriteria.RECOMMENDED.code -> descending(CharacterDbo::recommendationScore)
            else -> descending(CharacterDbo::createdAt)
        }

        val filters = and(
            CharacterDbo::visibility eq CharacterVisibility.PUBLIC.code,
            CharacterDbo::category `in` categories.map { it.code },
            if (searchQuery.isNotBlank()) {
                or(
                    CharacterDbo::name.regex(".*$searchQuery.*", "i"),
                    CharacterDbo::category.regex(".*$searchQuery.*", "i"),
                    regex(CharacterDbo::tags.name, ".*$searchQuery.*", "i"),
                    CharacterDbo::description.regex(".*$searchQuery.*", "i"),
                )
            } else EMPTY_BSON,
            if (authorId != null) {
                CharacterDbo::authorId eq authorId
            } else EMPTY_BSON,
            if (visibilityFilter != null) {
                CharacterDbo::visibility eq visibilityFilter
            } else EMPTY_BSON
        )

        val all = collection.find(filters)

        val skip = (page - 1) * size

        val sortedList = all.sort(sortCriteriaBson).toList()

        return sortedList.drop(skip).take(size)
    }


    private suspend fun getCharactersByIds(characterIds: List<String>): List<CharacterDbo> {
        val filters = and(
            CharacterDbo::id `in` characterIds,
            CharacterDbo::visibility eq CharacterVisibility.PUBLIC.code,
        )

        val characters = collection.find(filters).toList()

        // Сохраняем порядок из recommendedIds
        return characterIds.mapNotNull { id ->
            characters.find { it.id == id }
        }
    }


    suspend fun getCharactersByUserId(
        userId: String,
        includePrivate: Boolean = false
    ): List<CharacterDbo> {
        val filters = and(
            CharacterDbo::authorId eq userId,
            if (includePrivate) {
                EMPTY_BSON
            } else {
                CharacterDbo::visibility eq CharacterVisibility.PUBLIC.code
            }
        )

        return collection.find(filters).toList()
    }

    suspend fun getCharactersByUserId(
        session: ClientSession,
        userId: String,
        includePrivate: Boolean = false
    ): List<CharacterDbo> {
        val filters = and(
            CharacterDbo::authorId eq userId,
            if (includePrivate) {
                EMPTY_BSON
            } else {
                CharacterDbo::visibility eq CharacterVisibility.PUBLIC.code
            }
        )

        return collection.find(session, filters).toList()
    }

    suspend fun getUserCharactersWithCursor(
        userId: String,
        includePrivate: Boolean = false,
        visibility: Int? = null,
        cursor: String? = null,
        size: Int = 10
    ): CursorResult<CharacterDbo> {
        val beforeTime = cursor?.let { UtcTimestamp.parse(it) }

        val filters = and(
            CharacterDbo::authorId eq userId,
            if (includePrivate) {
                if (visibility != null) {
                    CharacterDbo::visibility eq visibility
                } else {
                    EMPTY_BSON
                }
            } else {
                CharacterDbo::visibility eq CharacterVisibility.PUBLIC.code
            },
            if (beforeTime != null) {
                CharacterDbo::createdAt lt beforeTime.toString()
            } else {
                EMPTY_BSON
            }
        )

        val characters = collection.find(filters)
            .sort(descending(CharacterDbo::createdAt))
            .limit(size + 1)
            .toList()

        val hasMore = characters.size > size
        val items = if (hasMore) characters.dropLast(1) else characters
        val nextCursor = if (hasMore) items.lastOrNull()?.createdAt?.toString() else null

        return CursorResult(
            items = items,
            nextCursor = nextCursor,
            hasMore = hasMore
        )
    }

    // Также добавить data class для результата:
    data class CursorResult<T>(
        val items: List<T>,
        val nextCursor: String?,
        val hasMore: Boolean
    ) {
        companion object {
            fun <T> empty(): CursorResult<T> = CursorResult(
                items = emptyList(),
                nextCursor = null,
                hasMore = false
            )

            fun <T> single(item: T): CursorResult<T> = CursorResult(
                items = listOf(item),
                nextCursor = null,
                hasMore = false
            )
        }
    }

    suspend fun getAllPublicCharactersForCategory(category: CharacterCategory): List<CharacterDbo> {
        return collection.find(
            and(
                CharacterDbo::visibility eq CharacterVisibility.PUBLIC.code,
                CharacterDbo::category eq category.code
            )
        ).toList()
    }

    suspend fun getAllPublicCharacters(): List<CharacterDbo> {
        return collection.find(CharacterDbo::visibility eq CharacterVisibility.PUBLIC.code).toList()
    }

    suspend fun getCharacter(characterId: String): CharacterDbo? {
        return collection.findOneById(characterId)
    }

    suspend fun getCharacter(session: ClientSession, characterId: String): CharacterDbo? {
        return collection.findOneById(characterId, session)
    }


    /**
     * UPDATE
     */
    suspend fun updateCharacter(
        session: ClientSession,
        characterId: String,
        name: String? = null,
        description: String? = null,
        prompt: String? = null,
        initialMessage: String? = null,
        visibility: Int? = null,
        pictureUrl: String? = null,
        pictureUrlThumbnail: String? = null,
        removePicture: Boolean? = null,
        category: CharacterCategory? = null,
        tags: List<CharacterTag>? = null,
    ) {
        val updates = mutableListOf<Bson>()

        name?.let { updates.add(setValue(CharacterDbo::name, it)) }
        description?.let { updates.add(setValue(CharacterDbo::description, it)) }
        prompt?.let { updates.add(setValue(CharacterDbo::prompt, it)) }
        initialMessage?.let { updates.add(setValue(CharacterDbo::initialMessage, it)) }
        visibility?.let { updates.add(setValue(CharacterDbo::visibility, it)) }
        pictureUrl?.let { updates.add(setValue(CharacterDbo::picUrl, it)) }
        pictureUrlThumbnail?.let { updates.add(setValue(CharacterDbo::picUrlThumbnail, it)) }
        removePicture?.let {
            if (it) {
                updates.add(setValue(CharacterDbo::picUrl, null))
                updates.add(setValue(CharacterDbo::picUrlThumbnail, null))
            }
        }
        category?.let { updates.add(setValue(CharacterDbo::category, it.code)) }
        tags?.let { updates.add(setValue(CharacterDbo::tags, it.map { tag -> tag.code })) }

        if (updates.isEmpty()) {
            return
        }

        collection.updateOneById(session, characterId, combine(*updates.toTypedArray()))
    }

    suspend fun updateAvgRating(session: ClientSession, characterId: String, newRating: Float) {
        collection.updateOneById(session, characterId, setValue(CharacterDbo::averageRating, newRating))
    }

    suspend fun updateTrendingScore(characterId: String, trendingScore: Float) {
        collection.updateOneById(characterId, setValue(CharacterDbo::trendingScore, trendingScore))
        collection.updateOneById(
            characterId,
            setValue(CharacterDbo::trendingScoreUpdatedAt, UtcTimestamp.now().toString())
        )
    }

    suspend fun updateRecommendationScore(characterId: String, recommendationScore: Float) {
        collection.updateOneById(
            characterId,
            combine(
                setValue(CharacterDbo::recommendationScore, recommendationScore),
                setValue(CharacterDbo::recommendationScoreUpdatedAt, UtcTimestamp.now().toString())
            )
        )
    }

    suspend fun updateCoOccurrenceScore(characterId: String, scores: Map<String, Float>) {
        collection.updateOneById(
            characterId,
            combine(
                setValue(CharacterDbo::coOccurrenceScore, scores),
                setValue(CharacterDbo::coOccurrenceScoreUpdatedAt, UtcTimestamp.now().toString())
            )
        )
    }

    suspend fun incrementReviewsCount(session: ClientSession, characterId: String, increment: Int) {
        collection.updateOneById(session, characterId, inc(CharacterDbo::totalReviews, increment))
    }

    suspend fun incrementChatsCount(session: ClientSession, characterId: String, increment: Int) {
        collection.updateOneById(session, characterId, inc(CharacterDbo::totalChats, increment))
    }

    suspend fun incrementMessagesCount(session: ClientSession, characterId: String, increment: Int) {
        collection.updateOneById(session, characterId, inc(CharacterDbo::totalMessages, increment))
    }

    /**
     * DELETE
     */
    suspend fun deleteCharacter(session: ClientSession, characterId: String) {
        collection.deleteOneById(session, characterId)
    }

    suspend fun deleteCharactersByIds(session: ClientSession, characterIds: List<String>) {
        if (characterIds.isEmpty()) return

        collection.deleteMany(
            session,
            CharacterDbo::id `in` characterIds
        )
    }
}