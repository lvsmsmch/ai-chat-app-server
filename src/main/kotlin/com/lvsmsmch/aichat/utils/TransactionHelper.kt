package com.lvsmsmch.aichat.utils

import com.mongodb.reactivestreams.client.ClientSession
import org.litote.kmongo.coroutine.CoroutineClient
import org.litote.kmongo.coroutine.abortTransactionAndAwait
import org.litote.kmongo.coroutine.commitTransactionAndAwait

class TransactionHelper(val client: CoroutineClient) {

    suspend fun <T: Any> withTransaction(transactions: suspend (ClientSession) -> T): T {
        val session = client.startSession()
        try {
            logger.info("TRANSACTION STARTED")
            session.startTransaction()

            val result = transactions(session)

            session.commitTransactionAndAwait()
            logger.info("TRANSACTION FINISHED")

            return result
        } catch (e: Exception) {
            logger.error("TRANSACTION FAILED !!!")
            logger.error("Error: ${e.message}", e)
            logger.error("Error type: ${e::class.simpleName}")
            session.abortTransactionAndAwait()
            throw e
        } finally {
            session.close()
        }
    }
}