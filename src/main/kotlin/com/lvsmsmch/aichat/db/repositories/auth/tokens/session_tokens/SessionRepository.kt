package com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens

import com.lvsmsmch.aichat.db.repositories.auth.tokens.TokenDbo
import com.lvsmsmch.aichat.db.repositories.auth.tokens.TokenVerifier
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.generateToken
import com.mongodb.client.model.Filters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq
import org.litote.kmongo.lt

@Serializable
data class SessionDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    override val token: String,
    override val createdAt: UtcTimestamp = UtcTimestamp.now(),
    override val expiresAt: UtcTimestamp = UtcTimestamp.now().addDays(1),
    val userId: String,
    val ipAddress: String,
): TokenDbo

class SessionRepository(
    override val collection: CoroutineCollection<SessionDbo>
): TokenVerifier<SessionDbo> {

    init {
        initializeIndexes()
    }

    /**
     * Initialize indexes for the collection
     */
    private fun initializeIndexes() {
        runBlocking {
            collection.ensureIndex(SessionDbo::token)
            collection.ensureIndex(SessionDbo::userId)
            collection.ensureIndex(SessionDbo::ipAddress)
        }
    }

    /**
     * Add a session to the collection
     */
    suspend fun createSession(userId: String, ipAddress: String): SessionDbo {
        val token = generateToken()
        val obj = SessionDbo(token = token, userId = userId, ipAddress = ipAddress)
        collection.insertOne(obj)
        return obj
    }

    /**
     * Get a session by its token string
     */
    suspend fun getSession(tokenString: String): SessionDbo? {
        return collection.findOne(SessionDbo::token eq tokenString)
    }

    /**
     * Delete a session
     */
    suspend fun deleteSessionByToken(token: String) {
        collection.deleteOne(SessionDbo::token eq token)
    }

    /**
     * Clear sessions that expired 1 year ago
     */
    suspend fun clearOldSessions() {
        val oneYearAgo = UtcTimestamp.now().addYears(-1)

        val result = collection.deleteMany(
            Filters.and(
                SessionDbo::expiresAt lt oneYearAgo
            )
        )
        println("Deleted ${result.deletedCount} sessions that expired more than a year ago")
    }
}