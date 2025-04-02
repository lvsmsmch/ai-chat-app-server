package com.lvsmsmch.aichat.db.repositories._trash.email_messages._base

import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.litote.kmongo.and
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq
import org.litote.kmongo.gt
import org.litote.kmongo.setValue

interface EmailMsgWithTokenDbo: EmailMsgDbo {
    val token: String
    val tokenExpiresAt: UtcTimestamp
    val isTokenUsed: Boolean
}

// Abstract base class specifically for token-based email repositories
abstract class BaseEmailMsgWithTokenRepository<T : EmailMsgWithTokenDbo>(
    collection: CoroutineCollection<T>
) : BaseEmailMsgRepository<T>(collection) {

    // Create a new email message object - implemented by specific repositories
    protected abstract fun createEmailMsg(email: String, token: String): T

    // Record that a new email was sent
    suspend fun recordNewEmailWasSent(
        email: String,
        token: String
    ): Boolean {
        // Check rate limiting before sending
        if (shouldRateLimit(email)) {
            return false
        }

        val msg = createEmailMsg(email, token)
        collection.insertOne(msg)
        return true
    }

    // Find valid token for verification
    suspend fun findValidToken(
        email: String,
        token: String
    ): T? {
        val now = UtcTimestamp.now()
        return collection.findOne(
            and(
                EmailMsgWithTokenDbo::email eq email,
                EmailMsgWithTokenDbo::token eq token,
                EmailMsgWithTokenDbo::tokenExpiresAt gt now,
                EmailMsgWithTokenDbo::isTokenUsed eq false
            )
        )
    }

    // Find valid token by token only (for direct link clicks)
    suspend fun findValidTokenByToken(token: String): T? {
        val now = UtcTimestamp.now()
        return collection.findOne(
            and(
                EmailMsgWithTokenDbo::token eq token,
                EmailMsgWithTokenDbo::tokenExpiresAt gt now,
                EmailMsgWithTokenDbo::isTokenUsed eq false
            )
        )
    }

    // Mark token as used
    suspend fun markTokenAsUsed(id: String): Boolean {
        val updateResult = collection.updateOneById(
            id,
            setValue(EmailMsgWithTokenDbo::isTokenUsed, true)
        )
        return updateResult.modifiedCount > 0
    }

    // Verify a token
    suspend fun verifyToken(email: String, token: String): Boolean {
        val validToken = findValidToken(email, token)
        if (validToken != null) {
            return markTokenAsUsed(validToken.id)
        }
        return false
    }

    // Verify a token by token only (for direct link clicks)
    suspend fun verifyTokenByToken(token: String): String? {
        val validToken = findValidTokenByToken(token)
        if (validToken != null && markTokenAsUsed(validToken.id)) {
            return validToken.email
        }
        return null
    }
}