package com.lvsmsmch.aichat._trash.db._trash.code_trackings

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class CodeTrackingDbo(
    @BsonId val email: String,
    val currentCode: Int? = null,
    val currentCodeFailedAttempts: Int = 0,
    val expiredCodes: List<Int> = emptyList(),
    val generatingNewCodesBlockedUntil: UtcTimestamp? = null,
) {
    fun canGenerateNewCode(): Boolean {
        return generatingNewCodesBlockedUntil == null || generatingNewCodesBlockedUntil.isInPast()
    }
}

abstract class CodeTrackingsBaseRepository(private val collection: CoroutineCollection<CodeTrackingDbo>) {

    private suspend fun getCodesForEmail(email: String): CodeTrackingDbo? {
        return collection.findOne(CodeTrackingDbo::email eq email)
    }

    private suspend fun editCodeTrackingForEmail(
        email: String, edit: suspend (CodeTrackingDbo) -> CodeTrackingDbo
    ) {
        val existingCodes = getCodesForEmail(email)
        if (existingCodes != null) {
            val updatedCodes = edit(existingCodes)
            collection.replaceOne(CodeTrackingDbo::email eq email, updatedCodes)
        }
    }

    suspend fun isCodeInExpiredList(code: Int, email: String): Boolean {
        return getOrCreate(email).expiredCodes.any { it == code }
    }

    suspend fun getOrCreate(email: String): CodeTrackingDbo {
        val existingCodes = collection.findOne(CodeTrackingDbo::email eq email)

        if (existingCodes != null) {
            return existingCodes
        }

        val newCodes = CodeTrackingDbo(
            email = email,
            currentCode = null,
            currentCodeFailedAttempts = 0,
            expiredCodes = emptyList(),
            generatingNewCodesBlockedUntil = null
        )

        collection.insertOne(newCodes)

        return newCodes
    }
    suspend fun handleCorrectCodeWasEntered(email: String) {
        editCodeTrackingForEmail(email) { codes ->
            return@editCodeTrackingForEmail codes
                .increaseCurrentCodeFailedAttempts(1)
                .let { codeTracking ->
                    if (codeTracking.currentCodeFailedAttempts >= 3) {
                        codeTracking.markCurrentCodeAsExpired().setThrottlingPeriod()
                    } else {
                        codeTracking
                    }
                }
        }
    }


    suspend fun handleIncorrectCodeWasEntered(email: String) {
        editCodeTrackingForEmail(email) { codes ->
            return@editCodeTrackingForEmail codes
                .increaseCurrentCodeFailedAttempts(1)
                .let { codeTracking ->
                    if (codeTracking.currentCodeFailedAttempts >= 3) {
                        codeTracking.markCurrentCodeAsExpired().setThrottlingPeriod()
                    } else {
                        codeTracking
                    }
                }
        }
    }

    suspend fun handleNewCodeWasSent(email: String, code: Int) {
        editCodeTrackingForEmail(email) { codeTracking ->
            codeTracking.markCurrentCodeAsExpired().setThrottlingPeriod().copy(currentCode = code)
        }
    }

    private fun CodeTrackingDbo.increaseCurrentCodeFailedAttempts(increaseBy: Int): CodeTrackingDbo {
        return copy(currentCodeFailedAttempts = currentCodeFailedAttempts + increaseBy)
    }

    private fun CodeTrackingDbo.markCurrentCodeAsExpired(): CodeTrackingDbo {
        return copy(
            currentCode = null,
            currentCodeFailedAttempts = 0,
            expiredCodes = (expiredCodes + currentCode).filterNotNull()
        )
    }

    private fun CodeTrackingDbo.setThrottlingPeriod(): CodeTrackingDbo {
        val blockUntil = when (expiredCodes.size) {
            0, 1 -> null
            2 -> UtcTimestamp.now().addMinutes(1)
            3 -> UtcTimestamp.now().addMinutes(5)
            4 -> UtcTimestamp.now().addMinutes(30)
            else -> UtcTimestamp.now().addDays(1)
        }

        return copy(generatingNewCodesBlockedUntil = blockUntil)
    }
}