package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.db.Db

/**
 * Маркер «этот вызов идёт внутри транзакции».
 *
 * Саму транзакцию Exposed держит в контексте корутины, поэтому передавать
 * наружу нечего. Но тип оставлен намеренно: он не даёт случайно позвать
 * транзакционный метод репозитория снаружи транзакции — раньше эту роль играл
 * Mongo-ClientSession, и все составные операции в ComplexQueryHelper построены
 * на том, что сессию нужно откуда-то получить.
 */
class DbSession internal constructor()

/**
 * Составная операция целиком: либо все запросы внутри применились, либо ни
 * один. Вложенные вызовы репозиториев присоединяются к этой же транзакции
 * автоматически — см. [Db.dbQuery].
 */
class TransactionHelper {

    suspend fun <T : Any> withTransaction(transactions: suspend (DbSession) -> T): T =
        Db.dbQuery {
            logger.info("TRANSACTION STARTED")
            try {
                val result = transactions(DbSession())
                logger.info("TRANSACTION FINISHED")
                result
            } catch (e: Exception) {
                // Откатывает сама Exposed, когда исключение выходит из блока
                logger.error("TRANSACTION FAILED !!!")
                logger.error("Error: ${e.message}", e)
                logger.error("Error type: ${e::class.simpleName}")
                throw e
            }
        }
}
