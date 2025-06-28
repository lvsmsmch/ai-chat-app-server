package com.lvsmsmch.aichat._trash.verification_codes

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.generateVerificationCode
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class VerificationCodeDbo(
    @BsonId val email: String,
    val code: Int,
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val expiresAt: UtcTimestamp = UtcTimestamp.now().addMinutes(30),
    val isUsed: Boolean = false,
    val isBlocked: Boolean = false,
    val lockoutStage: Int = 0,
    val lockoutUntil: UtcTimestamp = UtcTimestamp.now().addMinutes(1)
) {
    fun isAlive(): Boolean {
        return expiresAt.isInFuture() && !isBlocked && !isUsed
    }
}

abstract class BaseCodesRepository(
    protected val collection: CoroutineCollection<VerificationCodeDbo>
) {

    suspend fun canGenerateNewCode(email: String): Boolean {
        val latestCode = getCode(email)
        return latestCode == null || latestCode.lockoutUntil.isInPast()
    }

    suspend fun createNewVerificationCode(email: String): VerificationCodeDbo {
        val code = generateVerificationCode()
        val previousLockoutStage = getCode(email)?.lockoutStage ?: 0
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

        return VerificationCodeDbo(
            email = email,
            code = code,
            lockoutStage = newLockoutStage,
            lockoutUntil = lockoutUntil
        )
    }

    suspend fun save(obj: VerificationCodeDbo) {
        collection.insertOne(obj)
    }

    suspend fun getAliveCodeOrNull(email: String): VerificationCodeDbo? {
        return getCode(email)?.takeIf { it.isAlive() }
    }

    private suspend fun getCode(email: String): VerificationCodeDbo? {
        return collection.findOneById(email)
    }

    suspend fun markAsUsed(email: String): Boolean {
        val updateResult = collection.updateOneById(
            email, combine(
                setValue(VerificationCodeDbo::isUsed, true),
                setValue(VerificationCodeDbo::lockoutStage, 0),
            )
        )
        return updateResult.modifiedCount > 0
    }

    suspend fun markAsBlocked(email: String): Boolean {
        val updateResult = collection.updateOneById(email, setValue(VerificationCodeDbo::isBlocked, true))
        return updateResult.modifiedCount > 0
    }
}