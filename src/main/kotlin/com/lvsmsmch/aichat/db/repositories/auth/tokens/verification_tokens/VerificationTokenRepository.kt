package com.lvsmsmch.aichat.db.repositories.auth.tokens.verification_tokens

import com.lvsmsmch.aichat.db.repositories.auth.tokens.TokenDbo
import com.lvsmsmch.aichat.db.repositories.auth.tokens.TokenVerifier
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.generateToken
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class VerificationTokenDbo(
    @BsonId val email: String,
    override val token: String,
    override val createdAt: UtcTimestamp = UtcTimestamp.now(),
    override val expiresAt: UtcTimestamp = UtcTimestamp.now().addHours(24),
    val isUsed: Boolean = false,
    val isBlocked: Boolean = false,
    val lockoutStage: Int = 0,
    val lockoutUntil: UtcTimestamp = UtcTimestamp.now().addMinutes(1)
): TokenDbo {
    fun isAlive(): Boolean {
        return expiresAt.isInFuture() && !isUsed
    }
}

abstract class VerificationTokenRepository(
    override val collection: CoroutineCollection<VerificationTokenDbo>
): TokenVerifier<VerificationTokenDbo> {
    
    suspend fun canGenerateNewToken(email: String): Boolean {
        val latestCode = getToken(email)
        return latestCode == null || latestCode.lockoutUntil.isInPast()
    }

    suspend fun createNewVerificationToken(email: String): VerificationTokenDbo {
        val token = generateToken()
        val previousLockoutStage = getToken(email)?.lockoutStage ?: 0
        val newLockoutStage = previousLockoutStage + 1
        val addMinutes = when (newLockoutStage) {
            in 0..3 -> 1
            in 4..5 -> 3
            in 6..10 -> 30
            in 11..15 -> 180
            in 16..20 -> 360
            else -> 720
        }
        val lockoutUntil = UtcTimestamp.now().addMinutes(addMinutes.toLong())

        return VerificationTokenDbo(
            email = email,
            token = token,
            lockoutStage = newLockoutStage,
            lockoutUntil = lockoutUntil
        )
    }

    suspend fun save(obj: VerificationTokenDbo) {
        collection.insertOne(obj)
    }

    suspend fun getAliveTokenOrNull(email: String): VerificationTokenDbo? {
        return getToken(email)?.takeIf { it.isAlive() }
    }

    suspend fun getAliveTokenOrNullByToken(token: String): VerificationTokenDbo? {
        return getTokenByToken(token)?.takeIf { it.isAlive() }
    }

    private suspend fun getToken(email: String): VerificationTokenDbo? {
        return collection.findOneById(email)
    }

    private suspend fun getTokenByToken(token: String): VerificationTokenDbo? {
        return collection.findOne(VerificationTokenDbo::token eq token)
    }

    suspend fun markAsUsed(email: String): Boolean {
        val updateResult = collection.updateOneById(
            email, combine(
                setValue(VerificationTokenDbo::isUsed, true),
                setValue(VerificationTokenDbo::lockoutStage, 0),
            )
        )
        return updateResult.modifiedCount > 0
    }

    suspend fun markAsBlocked(email: String): Boolean {
        val updateResult = collection.updateOneById(email, setValue(VerificationTokenDbo::isBlocked, true))
        return updateResult.modifiedCount > 0
    }
}