package com.lvsmsmch.aichat.db.repositories._trash

import com.lvsmsmch.aichat.utils.EmailSender
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class VerificationEntityDbo<V>(
    @BsonId val id: String = ObjectId().toHexString(),
    val timestamp: UtcTimestamp = UtcTimestamp.now(),
    val email: String,
    val verificationValue: V,
    val expiresAt: UtcTimestamp = UtcTimestamp.now().addHours(24),
    val isUsed: Boolean = false,
    val isBlocked: Boolean = false,
    val nextLockoutUntil: UtcTimestamp = UtcTimestamp.now().addMinutes(1)
)

data class VerificationSendResult(
    val sentAt: UtcTimestamp,
    val expiresAt: UtcTimestamp,
    val nextAvailableAt: UtcTimestamp,
    val isNewEntity: Boolean
)

abstract class BaseVerificationRepository<V>(
    protected val collection: CoroutineCollection<VerificationEntityDbo<V>>
) {
    suspend fun sendVerification(
        email: String,
        emailSender: EmailSender,
    ): VerificationSendResult {
        val latestEntity = getLatestEntity(email)
        return when {
            canSendNewEntity(latestEntity) -> {
                val value = createUniqueVerificationValue()

                try {
                    sendEmail(email, value, emailSender)
                } catch (e: Exception) {
                    throw Exception("Couldn't send verification to email.")
                }

                val lockoutUntil = generateLockoutForNextEntity(email)
                val entity = VerificationEntityDbo(
                    email = email,
                    verificationValue = value,
                    nextLockoutUntil = lockoutUntil
                )

                try {
                    collection.insertOne(entity)
                } catch (e: Exception) {
                    throw Exception("Couldn't save verification to database.")
                }

                VerificationSendResult(
                    sentAt = entity.timestamp,
                    expiresAt = entity.expiresAt,
                    nextAvailableAt = entity.nextLockoutUntil,
                    isNewEntity = true
                )
            }

            isEntityAlive(latestEntity!!) -> {
                VerificationSendResult(
                    sentAt = latestEntity.timestamp,
                    expiresAt = latestEntity.expiresAt,
                    nextAvailableAt = latestEntity.nextLockoutUntil,
                    isNewEntity = false
                )
            }

            else -> throw Exception("Can't create new verification at this time.")
        }
    }

    suspend fun markEntityAsUsed(id: String): Boolean {
        val updateResult = collection.updateOneById(id, setValue(VerificationEntityDbo<V>::isUsed, true))
        return updateResult.modifiedCount > 0
    }

    suspend fun markEntityAsBlocked(id: String): Boolean {
        val updateResult = collection.updateOneById(id, setValue(VerificationEntityDbo<V>::isBlocked, true))
        return updateResult.modifiedCount > 0
    }

    suspend fun findValidEntity(email: String, value: V): VerificationEntityDbo<V>? {
        return findValidEntities(email).find { it.verificationValue == value }
    }

    private suspend fun getLatestEntity(email: String): VerificationEntityDbo<V>? {
        return collection.find(VerificationEntityDbo<V>::email eq email)
            .sort(descending(VerificationEntityDbo<V>::timestamp))
            .limit(1)
            .first()
    }

    private fun isEntityAlive(entity: VerificationEntityDbo<V>?): Boolean {
        return entity != null && entity.expiresAt.isInFuture() && !entity.isBlocked && !entity.isUsed
    }

    private fun canSendNewEntity(entity: VerificationEntityDbo<V>?): Boolean {
        return entity == null || entity.nextLockoutUntil.isInPast()
    }

    private suspend fun generateLockoutForNextEntity(email: String): UtcTimestamp {
        val oneHourAgo = UtcTimestamp.now().subtractDays(1)

        val recentEntities = collection.find(
            and(
                VerificationEntityDbo<V>::email eq email,
                VerificationEntityDbo<V>::timestamp gt oneHourAgo
            )
        ).toList()

        val minutes = getLockoutMinutesBasedOnRecentEntitiesSent(recentEntities.size).toLong()

        return UtcTimestamp.now().addMinutes(minutes)
    }

    private fun getLockoutMinutesBasedOnRecentEntitiesSent(count: Int):Int {
        return when (count) {
            in 0..3 -> 1
            in 4..5 -> 3
            in 6..10 -> 30
            in 11..20 -> 60
            else -> 180
        }
    }

    private suspend fun findValidEntities(email: String): List<VerificationEntityDbo<V>> {
        return collection.find(
            and(
                VerificationEntityDbo<V>::email eq email,
                VerificationEntityDbo<V>::expiresAt gt UtcTimestamp.now(),
                VerificationEntityDbo<V>::isUsed eq false,
                VerificationEntityDbo<V>::isBlocked eq false
            )
        ).toList()
    }


    protected abstract fun sendEmail(email: String, verificationValue: V, emailSender: EmailSender)
    protected abstract fun createUniqueVerificationValue(): V
}