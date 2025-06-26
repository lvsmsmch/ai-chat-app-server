package com.lvsmsmch.aichat.auth.database.tokens

import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

interface Usable {
    val isUsed: Boolean
}

interface UsableTokensRepository<T : Usable> {

    val collection: CoroutineCollection<T>

    suspend fun markAsUsed(token: String) {
        collection.updateOneById(token, setValue(Usable::isUsed, true))
    }
}