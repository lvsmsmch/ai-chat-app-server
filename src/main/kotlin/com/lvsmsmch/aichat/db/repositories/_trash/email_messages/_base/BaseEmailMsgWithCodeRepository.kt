package com.lvsmsmch.aichat.db.repositories._trash.email_messages._base

import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.litote.kmongo.and
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq
import org.litote.kmongo.gt
import org.litote.kmongo.setValue

interface EmailMsgWithCodeDbo: EmailMsgDbo {
    val code: Int
    val codeExpiresAt: UtcTimestamp
    val isCodeUsed: Boolean
}

abstract class BaseEmailMsgWithCodeRepository<T : EmailMsgWithCodeDbo>(
    collection: CoroutineCollection<T>
) : BaseEmailMsgRepository<T>(collection) {

    // Create a new email message object - implemented by specific repositories
    protected abstract fun createEmailMsg(email: String, code: Int): T

    // Record that a new email was sent
    suspend fun recordNewEmailWasSent(
        email: String,
        code: Int
    ): Boolean {
        // Check rate limiting before sending
        if (shouldRateLimit(email)) {
            return false
        }

        val msg = createEmailMsg(email, code)
        collection.insertOne(msg)
        return true
    }

    // Find valid code for verification
    suspend fun findValidCode(
        email: String,
        code: Int
    ): T? {
        val now = UtcTimestamp.now()
        return collection.findOne(
            and(
                EmailMsgWithCodeDbo::email eq email,
                EmailMsgWithCodeDbo::code eq code,
                EmailMsgWithCodeDbo::codeExpiresAt gt now,
                EmailMsgWithCodeDbo::isCodeUsed eq false
            )
        )
    }

    // Mark code as used
    suspend fun markCodeAsUsed(id: String): Boolean {
        val updateResult = collection.updateOneById(
            id,
            setValue(EmailMsgWithCodeDbo::isCodeUsed, true)
        )
        return updateResult.modifiedCount > 0
    }

    // Verify a code
    suspend fun verifyCode(email: String, code: Int): Boolean {
        val validCode = findValidCode(email, code)
        if (validCode != null) {
            return markCodeAsUsed(validCode.id)
        }
        return false
    }
}