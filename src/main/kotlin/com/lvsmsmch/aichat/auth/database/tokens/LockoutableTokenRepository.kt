package com.lvsmsmch.aichat.auth.database.tokens

import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.setValue

interface Lockoutable {
    val lockoutStage: Int
    val lockoutUntil: UtcTimestamp
}

interface LockoutableTokensRepository<T : Lockoutable> {

    val collection: CoroutineCollection<T>

    suspend fun isLockoutActive(previous: Lockoutable): Boolean {
        return previous.lockoutUntil.isInFuture()
    }

    suspend fun isLockoutInPast(previous: Lockoutable): Boolean {
        return previous.lockoutUntil.isInPast()
    }

    suspend fun createNewLockoutable(previous: Lockoutable? = null): Lockoutable {
        val previousLockoutStage = previous?.lockoutStage ?: 0
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
        return object : Lockoutable {
            override val lockoutStage = newLockoutStage
            override val lockoutUntil = lockoutUntil
        }
    }

    suspend fun resetLockout(token: String) {
        collection.updateOneById(token, setValue(Lockoutable::lockoutStage, 0))
        collection.updateOneById(token, setValue(Lockoutable::lockoutUntil, UtcTimestamp.now()))
    }
}