package com.lvsmsmch.aichat.db.repositories.auth.tokens.single_use_tokens

import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

interface Usable {
    val isUsed: Boolean
}

interface UsableTokensRepository<T : Usable> {

    val collection: CoroutineCollection<T>

        suspend fun markAsUsedByToken(token: String): Boolean {
        val updateResult = collection.updateOneById(
            token, setValue(Usable::isUsed, true)
        )
        return updateResult.modifiedCount > 0
    }
}