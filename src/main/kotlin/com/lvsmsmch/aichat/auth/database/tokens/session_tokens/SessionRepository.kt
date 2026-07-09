package com.lvsmsmch.aichat.auth.database.tokens.session_tokens

import com.lvsmsmch.aichat.auth.database.tokens.TokenDbo
import com.lvsmsmch.aichat.auth.database.tokens.TokenRepository
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.lvsmsmch.aichat.utils.generateToken
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class SessionDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    override val token: String,
    override val createdAt: String = UtcTimestamp.now().toString(),
    override val expiresAt: String = UtcTimestamp.now().addDays(100_000).toString(),
    val userId: String,
    val ipAddress: String,
): TokenDbo

class SessionRepository(
    override val collection: CoroutineCollection<SessionDbo>
): TokenRepository<SessionDbo> {


    suspend fun ensureIndexes() {
        collection.ensureIndex(SessionDbo::token)
        collection.ensureIndex(SessionDbo::userId)
        collection.ensureIndex(SessionDbo::ipAddress)
    }



    val databaseEventsFlow = createDatabaseEventsFlow(collection)


    suspend fun createSession(userId: String, ipAddress: String): SessionDbo {
        val token = generateToken()
        val obj = SessionDbo(token = token, userId = userId, ipAddress = ipAddress)
        collection.insertOne(obj)
        return obj
    }
}