package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.auth.database.AuthCodeRepository
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Убирает истёкшие коды из писем. Проверка кода и так смотрит на срок, так что
 * это чистка ради размера таблицы: неиспользованные коды иначе копились бы
 * вечно (каждый запрос «отправить ещё раз» оставляет запись).
 */
fun configureAuthCodesCleanupUpdater(
    databaseScope: CoroutineScope,
    authCodeRepository: AuthCodeRepository,
    intervalMinutes: Long = 60,
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                val removed = authCodeRepository.deleteExpired()
                if (removed > 0) logger.debug("Удалено истёкших кодов из писем: $removed")
                delay(TimeUnit.MINUTES.toMillis(intervalMinutes))
            } catch (e: CancellationException) {
                logger.debug("Auth codes cleanup cancelled")
                break
            } catch (e: Exception) {
                logger.error("Ошибка чистки кодов: ${e.message}", e)
                delay(TimeUnit.MINUTES.toMillis(10))
            }
        }
    }

    return parentJob
}
