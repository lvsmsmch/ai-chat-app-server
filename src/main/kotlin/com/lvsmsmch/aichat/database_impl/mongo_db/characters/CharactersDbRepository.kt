package com.lvsmsmch.aichat.database_impl.mongo_db.characters

import com.lvsmsmch.aichat.database_impl.mongo_db.reviews.ReviewDbo
import com.lvsmsmch.aichat.domain.other.filters.CharacterFilter
import com.lvsmsmch.aichat.domain.other.filters.ReviewFilter
import org.litote.kmongo.*
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import org.bson.conversions.Bson

class CharactersDbRepository(database: MongoDatabase) {
    private val collection = database.getCollection<CharacterDbo>("characters")

    /**
     * CHARACTERS:
     */

    suspend fun getCharactersWithFilter(
        filter: CharacterFilter,
        searchQuery: String = "",
        limit: Int = 10,
        skip: Int = 0
    ): List<CharacterDbo> {
        val sortingStrategy = when (filter) {
            CharacterFilter.NEWEST -> Sorts.descending(CharacterDbo::publishedAt.name)
            CharacterFilter.OLDEST -> Sorts.ascending(CharacterDbo::publishedAt.name)
            CharacterFilter.HIGHEST_RATING -> Sorts.descending(CharacterDbo::averageRating.name)
            CharacterFilter.LOWEST_RATING -> Sorts.ascending(CharacterDbo::averageRating.name)
            CharacterFilter.MOST_POPULAR -> Sorts.descending(CharacterDbo::totalMessages.name)
            CharacterFilter.LEAST_POPULAR -> Sorts.ascending(CharacterDbo::totalMessages.name)
        }

        val aggregationPipeline = mutableListOf<Bson>()


        searchQuery.takeIf { it.isNotBlank() }?.let { query ->
            val searchFilter = Filters.or(
                Filters.regex(CharacterDbo::name.name, ".*$query.*", "i"),
                Filters.regex(CharacterDbo::description.name, ".*$query.*", "i")
            )
            aggregationPipeline.add(Aggregates.match(searchFilter))
        }


        aggregationPipeline.add(Aggregates.sort(sortingStrategy))
        aggregationPipeline.add(Aggregates.skip(skip))
        aggregationPipeline.add(Aggregates.limit(limit))



        return collection.aggregate(aggregationPipeline).toList()
    }

    suspend fun getCharacterById(characterId: String): CharacterDbo? {
        return collection.findOneById(characterId)
    }

    suspend fun updateCharacter(characterId: String, updatedCharacter: CharacterDbo): Boolean {
        val result = collection.replaceOne(CharacterDbo::id eq characterId, updatedCharacter)
        return result.modifiedCount > 0
    }

    suspend fun deleteCharacter(characterId: String): Boolean {
        val result = collection.deleteOne(CharacterDbo::id eq characterId)
        return result.deletedCount > 0
    }

    suspend fun characterExists(characterId: String): Boolean {
        return collection.countDocuments(CharacterDbo::id eq characterId) > 0
    }


    /**
     * REVIEWS:
     */

    suspend fun getReviewsForCharacter(
        characterId: String,
        filter: ReviewFilter,
        limit: Int = 10,
        skip: Int = 0
    ): List<ReviewDbo> {
        val sortingStrategy = when (filter) {
            ReviewFilter.NEWEST -> Sorts.descending(ReviewDbo::publishedAt.name)
            ReviewFilter.OLDEST -> Sorts.ascending(ReviewDbo::publishedAt.name)
            ReviewFilter.HIGHEST_RATING -> Sorts.descending(ReviewDbo::rating.name)
            ReviewFilter.LOWEST_RATING -> Sorts.ascending(ReviewDbo::rating.name)
        }

        val aggregationPipeline = listOf(
            Aggregates.match(CharacterDbo::id eq characterId),
            Aggregates.unwind("\$${CharacterDbo::reviews.name}"),
            Aggregates.sort(sortingStrategy),
            Aggregates.skip(skip),
            Aggregates.limit(limit),
            Aggregates.replaceRoot("\$${CharacterDbo::reviews.name}"),
        )

        return collection.aggregate(aggregationPipeline, ReviewDbo::class.java).toList()
    }

    suspend fun addReviewToCharacter(characterId: String, review: ReviewDbo): Boolean {
        val result = collection.updateOne(
            CharacterDbo::id eq characterId,
            push(CharacterDbo::reviews, review)
        )
        return result.modifiedCount > 0
    }

    suspend fun deleteReviewFromCharacter(characterId: String, reviewId: String): Boolean {
        val result = collection.updateOne(
            CharacterDbo::id eq characterId,
            pullByFilter(CharacterDbo::reviews, ReviewDbo::id eq reviewId)
        )
        return result.modifiedCount > 0
    }
}
