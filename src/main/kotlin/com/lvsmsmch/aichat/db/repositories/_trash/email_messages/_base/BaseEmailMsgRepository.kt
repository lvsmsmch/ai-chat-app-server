package com.lvsmsmch.aichat.db.repositories._trash.email_messages._base

import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.litote.kmongo.and
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq
import org.litote.kmongo.gt
import org.litote.kmongo.lt

interface EmailMsgDbo {
    val id: String
    val timestamp: UtcTimestamp
    val email: String
}

// Abstract base class for email message repositories
abstract class BaseEmailMsgRepository<T : EmailMsgDbo>(
    protected val collection: CoroutineCollection<T>
) {
    // Check how many emails were sent to this address recently
    suspend fun getRecentEmailsCount(
        email: String,
        periodInMinutes: Long = 60
    ): Int {
        val cutoffTime = UtcTimestamp.now().addMinutes(-periodInMinutes)
        return collection.countDocuments(
            and(
                EmailMsgDbo::email eq email,
                EmailMsgDbo::timestamp gt cutoffTime
            )
        ).toInt()
    }

    // Check if we should rate limit
    suspend fun shouldRateLimit(
        email: String,
        maxEmailsPerHour: Int = 5
    ): Boolean {
        return getRecentEmailsCount(email) >= maxEmailsPerHour
    }

    // Clean up old messages
    suspend fun clearOldMessages(olderThanDays: Long = 7) {
        val cutoffTime = UtcTimestamp.now().addDays(-olderThanDays)
        collection.deleteMany(EmailMsgDbo::timestamp lt cutoffTime)
    }
}
