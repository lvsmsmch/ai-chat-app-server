package com.lvsmsmch.aichat.db

import com.lvsmsmch.aichat.db.objects.CharacterDbo
import com.lvsmsmch.aichat.db.objects.ReviewDbo
import com.lvsmsmch.aichat.db.objects.SessionDbo
import com.lvsmsmch.aichat.db.objects.UserDbo
import com.lvsmsmch.aichat.db.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.CharacterFilter
import com.lvsmsmch.aichat.utils.ReviewFilter
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.findOneById
import org.litote.kmongo.getCollection

class Database() {

    private val mongoClientSettings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString("mongodb://localhost:27017"))
        .build()
    private val mongoClient = MongoClients.create(mongoClientSettings)
    private val database = mongoClient.getDatabase("ai_chat_app_database")

    private val usersCollection = database.getCollection<UserDbo>("users")
    private val sessionsCollection = database.getCollection<SessionDbo>("sessions")
    private val charactersCollection = database.getCollection<CharacterDbo>("characters")


    /**
     * USER
     */


    suspend fun getUserById(userId: String): UserDbo? {
        return usersCollection.findOneById(userId)
    }


    suspend fun addUser(
        isVerified: Boolean,
        loginMethod: String,
        email: String,
        hashedPassword: String,
        oauthId: String,
        name: String,
        profilePictureUrl: String
    ): Boolean {
        val newUser = UserDbo(
            id = ObjectId().toHexString(),
            createdAt = UtcTimestamp.now(),
            isVerified = isVerified,
            loginMethod = loginMethod,
            email = email,
            hashedPassword = hashedPassword,
            oauthId = oauthId,
            name = name,
            profilePicUrl = profilePictureUrl
        )
        usersCollection.insertOne(newUser)
        return true
    }


    suspend fun updateUserName(userId: String, name: String): Boolean {
        val updateResult = usersCollection.updateOne(
            Filters.eq(UserDbo::id.name, userId),
            Updates.set(UserDbo::name.name, name)
        )
        return updateResult.modifiedCount > 0
    }

    suspend fun updateUserProfilePicture(userId: String, profilePictureUrl: String): Boolean {
        val updateResult = usersCollection.updateOne(
            Filters.eq(UserDbo::id.name, userId),
            Updates.set(UserDbo::profilePicUrl.name, profilePictureUrl)
        )
        return updateResult.modifiedCount > 0
    }


    suspend fun updateUserVerifiedStatus(userId: String, verified: Boolean): Boolean {
        val updateResult = usersCollection.updateOne(
            Filters.eq(UserDbo::id.name, userId),
            Updates.set(UserDbo::isVerified.name, verified)
        )
        return updateResult.modifiedCount > 0
    }


    suspend fun updateUserPassword(userId: String, hashedPassword: Boolean): Boolean {
        val updateResult = usersCollection.updateOne(
            Filters.eq(UserDbo::id.name, userId),
            Updates.set(UserDbo::hashedPassword.name, hashedPassword)
        )
        return updateResult.modifiedCount > 0
    }


    suspend fun deleteUser(userId: String): Boolean {
        val deleteResult = usersCollection.deleteOne(Filters.eq(UserDbo::id.name, userId))
        return deleteResult.deletedCount > 0
    }


    /**
     * SESSION
     */

    suspend fun addSession(authToken: String, userId: String, expiresAt: UtcTimestamp): Boolean {
        val session = SessionDbo(
            authToken = authToken,
            userId = userId,
            expiresAt = expiresAt
        )
        sessionsCollection.insertOne(session)
        return true
    }

    suspend fun getSession(authToken: String): SessionDbo? {
        return sessionsCollection.findOne(Filters.eq(SessionDbo::authToken.name, authToken))
    }


    /**
     * CHARACTER
     */

    suspend fun getCharacters(filter: Int, searchQuery: String, limit: Int, skip: Int): List<CharacterDbo> {
        val sortingStrategy = when (filter) {
            CharacterFilter.NEWEST.code -> Sorts.descending(CharacterDbo::publishedAt.name)
            CharacterFilter.OLDEST.code -> Sorts.ascending(CharacterDbo::publishedAt.name)
            CharacterFilter.HIGHEST_RATING.code -> Sorts.descending(CharacterDbo::averageRating.name)
            CharacterFilter.LOWEST_RATING.code -> Sorts.ascending(CharacterDbo::averageRating.name)
            CharacterFilter.MOST_POPULAR.code -> Sorts.descending(CharacterDbo::totalMessages.name)
            CharacterFilter.LEAST_POPULAR.code -> Sorts.ascending(CharacterDbo::totalMessages.name)
            else -> Sorts.descending(CharacterDbo::publishedAt.name)
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



        return charactersCollection.aggregate(aggregationPipeline).toList()
    }

    suspend fun getCharacter(characterId: String): CharacterDbo? {
        return charactersCollection.findOneById(characterId)
    }

    suspend fun addCharacter(
        name: String,
        description: String,
        prompt: String,
        publishedBy: String,
        pictureUrl: String
    ): Boolean {
        val newCharacter = CharacterDbo(
            id = ObjectId().toHexString(),
            name = name,
            description = description,
            picUrl = pictureUrl,
            prompt = prompt,
            publishedAt = UtcTimestamp.now(),
            publishedBy = publishedBy,
            totalMessages = 0,
            totalReviews = 0,
            averageRating = 0f,
            reviews = emptyList()
        )
        charactersCollection.insertOne(newCharacter)
        return true
    }

    suspend fun deleteCharacter(characterId: String): Boolean {
        val result = charactersCollection.deleteOne(CharacterDbo::id eq characterId)
        return result.deletedCount > 0
    }


    /**
     * REVIEW
     */


    suspend fun getAllReviewsForCharacter(characterId: String, filter: Int, limit: Int, skip: Int): List<ReviewDbo> {
        val sortingStrategy = when (filter) {
            ReviewFilter.NEWEST.code -> Sorts.descending(ReviewDbo::publishedAt.name)
            ReviewFilter.OLDEST.code -> Sorts.ascending(ReviewDbo::publishedAt.name)
            ReviewFilter.HIGHEST_RATING.code -> Sorts.descending(ReviewDbo::rating.name)
            ReviewFilter.LOWEST_RATING.code -> Sorts.ascending(ReviewDbo::rating.name)
            else -> Sorts.descending(ReviewDbo::publishedAt.name)
        }

        val aggregationPipeline = listOf(
            Aggregates.match(CharacterDbo::id eq characterId),
            Aggregates.unwind("\$${CharacterDbo::reviews.name}"),
            Aggregates.sort(sortingStrategy),
            Aggregates.skip(skip),
            Aggregates.limit(limit),
            Aggregates.replaceRoot("\$${CharacterDbo::reviews.name}"),
        )

        return charactersCollection.aggregate(aggregationPipeline, ReviewDbo::class.java).toList()
    }


    suspend fun getUsersReviewForCharacter(userId: String, characterId: String): ReviewDbo? {
        val character = charactersCollection.findOne(Filters.eq("id", characterId))
        return character?.reviews?.find { it.publishedBy == userId }
    }

    suspend fun addReview(userId: String, characterId: String, rating: Int, text: String): Boolean {
        val review = ReviewDbo(
            id = ObjectId().toHexString(),
            isAnonymous = false,
            publishedAt = UtcTimestamp.now(),
            publishedBy = userId,
            rating = rating,
            text = text
        )

        val updateResult = charactersCollection.updateOne(
            Filters.eq(CharacterDbo::id.name, characterId),
            Updates.push(CharacterDbo::reviews.name, review)
        )
        return updateResult.modifiedCount > 0
    }


    suspend fun updateReview(userId: String, characterId: String, rating: Int, text: String): Boolean {
        val reviewField = CharacterDbo::reviews.name
        val publishedByField = ReviewDbo::publishedBy.name
        val ratingField = ReviewDbo::rating.name
        val textField = ReviewDbo::text.name
        val publishedAtField = ReviewDbo::publishedAt.name

        val updateResult = charactersCollection.updateOne(
            Filters.and(
                Filters.eq(CharacterDbo::id.name, characterId),
                Filters.eq("$reviewField.$publishedByField", userId) // Access nested field properly
            ),
            Updates.combine(
                Updates.set("$reviewField.$[].$ratingField", rating),
                Updates.set("$reviewField.$[].$textField", text),
                Updates.set("$reviewField.$[].$publishedAtField", UtcTimestamp.now())
            )
        )

        return updateResult.modifiedCount > 0
    }

    suspend fun deleteReview(userId: String, characterId: String): Boolean {
        val updateResult = charactersCollection.updateOne(
            Filters.eq(CharacterDbo::id.name, characterId),
            Updates.pull(CharacterDbo::reviews.name, Filters.eq(ReviewDbo::publishedBy.name, userId))
        )

        return updateResult.modifiedCount > 0
    }
}