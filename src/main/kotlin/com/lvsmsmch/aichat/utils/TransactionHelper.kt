package com.lvsmsmch.aichat.utils

import com.mongodb.reactivestreams.client.ClientSession
import org.litote.kmongo.coroutine.CoroutineClient

class TransactionHelper(val client: CoroutineClient) {

    suspend fun <T: Any> withTransaction(transactions: suspend (ClientSession) -> T): T {
        val session = client.startSession()
        try {
            session.startTransaction()
            val result = transactions(session)
            session.commitTransaction()
            return result
        } catch (e: Exception) {
            session.abortTransaction()
            throw e
        } finally {
            session.close()
        }
    }
}