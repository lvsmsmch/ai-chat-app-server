package com.lvsmsmch.aichat.db.repositories.content

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
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
    val publishedAt: UtcTimestamp = UtcTimestamp.now(),
    val publisherId: String,
    val publisherUsername: String,
    val name: String,
    val description: String,
    val prompt: String,
    val picUrl: String,
    val totalChats: Int = 0,
    val totalMessages: Int = 0,
    val totalReviews: Int = 0,
    val averageRating: Float = 0f,
)

class CharactersRepository(
    private val collection: CoroutineCollection<CharacterDbo>
) {
    suspend fun getCharacters(searchQuery: String, filter: Int, limit: Int, skip: Int): List<CharacterDbo> {
        val sortCriteria = when (filter) {
            CharacterFilter.NEWEST.code -> descending(CharacterDbo::publishedAt)
            CharacterFilter.OLDEST.code -> ascending(CharacterDbo::publishedAt)
            CharacterFilter.HIGHEST_RATING.code -> descending(CharacterDbo::averageRating)
            CharacterFilter.LOWEST_RATING.code -> ascending(CharacterDbo::averageRating)
            CharacterFilter.MOST_POPULAR.code -> descending(CharacterDbo::totalMessages)
            CharacterFilter.LEAST_POPULAR.code -> ascending(CharacterDbo::totalMessages)
            else -> descending(CharacterDbo::publishedAt)
        }

        val pipeline = buildList {
            if (searchQuery.isNotBlank()) {
                val searchFilter = or(
                    CharacterDbo::name.regex(".*$searchQuery.*", "i"),
                    CharacterDbo::description.regex(".*$searchQuery.*", "i")
                )
                add(match(searchFilter))
            }

            add(sort(sortCriteria))
            add(skip(skip))
            add(limit(limit))
        }

        return collection.aggregate<CharacterDbo>(pipeline).toList()
    }

    suspend fun getCharacter(characterId: String): CharacterDbo? {
        return collection.findOneById(characterId)
    }

    suspend fun addCharacter(
        name: String,
        description: String,
        prompt: String,
        publisherId: String,
        pictureUrl: String
    ): Boolean {
        val newCharacter = CharacterDbo(
            name = name,
            description = description,
            picUrl = pictureUrl,
            prompt = prompt,
            publishedAt = UtcTimestamp.now(),
            publisherId = publisherId
        )
        return collection.insertOne(newCharacter).wasAcknowledged()
    }

    suspend fun incrementMessageCount(characterId: String): Boolean {
        val updateResult = collection.updateOneById(
            characterId,
            inc(CharacterDbo::totalMessages, 1)
        )
        return updateResult.modifiedCount > 0
    }

    suspend fun deleteCharacter(characterId: String): Boolean {
        return collection.deleteOneById(characterId).deletedCount > 0
    }
}