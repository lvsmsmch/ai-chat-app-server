package com.lvsmsmch.aichat.db.repositories.auth.attempts

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.and
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq
import org.litote.kmongo.gt
import org.litote.kmongo.lt

@Serializable
data class AttemptDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val timestamp: UtcTimestamp = UtcTimestamp.now(),
    val ipAddress: String,
    val email: String,
    val successful: Boolean = false
)

abstract class BaseAttemptTracker(
    protected val collection: CoroutineCollection<AttemptDbo>
) {

    suspend fun recordAttempt(ipAddress: String, email: String, successful: Boolean) {
        val attempt = AttemptDbo(
            ipAddress = ipAddress,
            email = email,
            successful = successful
        )
        collection.insertOne(attempt)
    }

    suspend fun getRecentFailedAttemptsByEmail(
        email: String,
        periodInMinutes: Long = 10
    ): Int {
        val cutoffTime = UtcTimestamp.now().addMinutes(-periodInMinutes)
        return collection.countDocuments(
            and(
                AttemptDbo::email eq email,
                AttemptDbo::successful eq false,
                AttemptDbo::timestamp gt cutoffTime
            )
        ).toInt()
    }

    suspend fun getRecentFailedAttemptsByIpAndEmail(
        ipAddress: String,
        email: String,
        periodInMinutes: Long = 10
    ): Int {
        val cutoffTime = UtcTimestamp.now().addMinutes(-periodInMinutes)
        return collection.countDocuments(
            and(
                AttemptDbo::ipAddress eq ipAddress,
                AttemptDbo::email eq email,
                AttemptDbo::successful eq false,
                AttemptDbo::timestamp gt cutoffTime
            )
        ).toInt()
    }

    suspend fun clearAttemptsForEmail(email: String) {
        collection.deleteMany(AttemptDbo::email eq email)
    }

    suspend fun clearOldAttempts(olderThanMinutes: Long = 60 * 24) {
        val cutoffTime = UtcTimestamp.now().addMinutes(-olderThanMinutes)
        collection.deleteMany(AttemptDbo::timestamp lt cutoffTime)
    }
}
