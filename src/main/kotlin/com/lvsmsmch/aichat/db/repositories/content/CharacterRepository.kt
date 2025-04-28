package com.lvsmsmch.aichat.db.repositories.content

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

enum class CharacterFilter(val code: Int) {
    NEWEST(0),
    OLDEST(1),
    HIGHEST_RATING(2),
    LOWEST_RATING(3),
    MOST_POPULAR(4),
    LEAST_POPULAR(5),
}


@Serializable
data class CharacterDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val authorId: String,
    val name: String,
    val description: String,
    val prompt: String,
    val picUrl: String,
    val isPublic: Boolean,
    val totalChats: Int = 0,
    val totalMessages: Int = 0,
    val totalReviews: Int = 0,
    val averageRating: Float = 0f,
)

class CharacterRepository(
    private val collection: CoroutineCollection<CharacterDbo>,
    private val onCharacterChanged: (dbo: CharacterDbo) -> Unit,
) {


    /**
     * CREATE
     */
    suspend fun addCharacter(
        publisherId: String,
        name: String,
        description: String,
        prompt: String,
        pictureUrl: String
    ): Boolean {
        val newCharacter = CharacterDbo(
            authorId = publisherId,
            name = name,
            description = description,
            prompt = prompt,
            picUrl = pictureUrl,
            isPublic = true,
        )
        return collection.insertOne(newCharacter).wasAcknowledged()
    }


    /**
     * READ
     */
    suspend fun getPublicCharacters(
        searchQuery: String,
        filter: Int,
        limit: Int,
        skip: Int
    ): List<CharacterDbo> {
        val sortCriteria = when (filter) {
            CharacterFilter.NEWEST.code -> descending(CharacterDbo::createdAt)
            CharacterFilter.OLDEST.code -> ascending(CharacterDbo::createdAt)
            CharacterFilter.HIGHEST_RATING.code -> descending(CharacterDbo::averageRating)
            CharacterFilter.LOWEST_RATING.code -> ascending(CharacterDbo::averageRating)
            CharacterFilter.MOST_POPULAR.code -> descending(CharacterDbo::totalMessages)
            CharacterFilter.LEAST_POPULAR.code -> ascending(CharacterDbo::totalMessages)
            else -> descending(CharacterDbo::createdAt)
        }

        val filters = and(
            CharacterDbo::isPublic eq true,
            if (searchQuery.isNotBlank()) {
                or(
                    CharacterDbo::name.regex(".*$searchQuery.*", "i"),
                    CharacterDbo::description.regex(".*$searchQuery.*", "i")
                )
            } else EMPTY_BSON
        )

        return collection.find(filters)
            .sort(sortCriteria)
            .skip(skip)
            .limit(limit)
            .toList()
    }

    suspend fun getCharacter(characterId: String): CharacterDbo? {
        return collection.findOneById(characterId)
    }

    suspend fun getCharacters(userId: String, isPublic: Boolean): List<CharacterDbo> {
        val filter = and(
            CharacterDbo::authorId eq userId,
            CharacterDbo::isPublic eq isPublic,
        )

        return collection.find(filter).toList()
    }


    /**
     * UPDATE
     */
    suspend fun updateCharacter(
        characterId: String,
        name: String? = null,
        description: String? = null,
        prompt: String? = null,
        pictureUrl: String? = null
    ) {
        val updates = mutableListOf<Bson>()

        name?.let { updates.add(setValue(CharacterDbo::name, it)) }
        description?.let { updates.add(setValue(CharacterDbo::description, it)) }
        prompt?.let { updates.add(setValue(CharacterDbo::prompt, it)) }
        pictureUrl?.let { updates.add(setValue(CharacterDbo::picUrl, it)) }

        if (updates.isEmpty()) {
            return
        }

        collection.updateOneById(characterId, combine(*updates.toTypedArray()))
    }


    /**
     * DELETE
     */
    suspend fun deleteCharacter(characterId: String) {
        collection.deleteOneById(characterId)
    }


    /**
     * OTHER
     */

    suspend fun onChatAdded(characterId: String) {
        collection.updateOneById(characterId, inc(CharacterDbo::totalChats, 1))
    }

    suspend fun onMessageAdded(characterId: String) {
        collection.updateOneById(characterId, inc(CharacterDbo::totalMessages, 1))
    }

    suspend fun onReviewAdded(characterId: String) {
        collection.updateOneById(characterId, inc(CharacterDbo::totalReviews, 1))
    }

    suspend fun onReviewDeleted(characterId: String) {
        collection.updateOneById(characterId, inc(CharacterDbo::totalReviews, -1))
    }

    suspend fun onAverageRatingChanged(characterId: String, averageRating: Float) {
        collection.updateOneById(characterId, setValue(CharacterDbo::averageRating, averageRating))
    }
}