package com.lvsmsmch.aichat._trash

import com.lvsmsmch.aichat.auth.database.tokens.*
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.lvsmsmch.aichat.utils.generateToken
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class VerificationTokenDbo(
    @BsonId val email: String,
    override val token: String,
    override val createdAt: UtcTimestamp = UtcTimestamp.now(),
    override val expiresAt: UtcTimestamp = UtcTimestamp.now().addHours(24),
    override val lockoutStage: Int = 0,
    override val lockoutUntil: UtcTimestamp = UtcTimestamp.now().addMinutes(1),
    override val isUsed: Boolean = false,
) : TokenDbo, Usable, Lockoutable {

    val isExpired: Boolean
        get() = expiresAt.isInPast()

    fun isAlive(): Boolean {
        return expiresAt.isInFuture() && !isUsed
    }
}

abstract class VerificationTokenRepository(
    final override val collection: CoroutineCollection<VerificationTokenDbo>
) : TokenRepository<VerificationTokenDbo>,
    LockoutableTokensRepository<VerificationTokenDbo>,
    UsableTokensRepository<VerificationTokenDbo> {


    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    suspend fun canGenerateNewToken(email: String): Boolean {
        val currentToken = getTokenByEmail(email)
        return currentToken == null || isLockoutInPast(currentToken)
    }

    suspend fun createAndSaveNewToken(email: String): VerificationTokenDbo {
        val lockoutable = createNewLockoutable(previous = getTokenByEmail(email))
        val tokenDbo = VerificationTokenDbo(
            email = email,
            token = generateToken(),
            lockoutStage = lockoutable.lockoutStage,
            lockoutUntil = lockoutable.lockoutUntil
        )
        collection.insertOne(tokenDbo)
        return tokenDbo
    }

    suspend fun getTokenByEmail(email: String): VerificationTokenDbo? {
        return collection.findOneById(email)
    }
}