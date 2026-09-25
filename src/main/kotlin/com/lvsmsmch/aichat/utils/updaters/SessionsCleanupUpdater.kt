package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Удаляет истёкшие сессии. Проверка токена и так смотрит на срок, так что это
 * чистка ради размера таблицы: раньше сессии жили вечно и накапливались с
 * каждым запуском приложения.
 */
fun configureSessionsCleanupUpdater(
    databaseScope: CoroutineScope,
    sessionRepository: SessionRepository,
    intervalHours: Long = 24,
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                val removed = sessionRepository.deleteExpired()
                if (removed > 0) logger.info("Удалено истёкших сессий: $removed")
                delay(TimeUnit.HOURS.toMillis(intervalHours))
            } catch (e: CancellationException) {
                logger.debug("Sessions cleanup cancelled")
                break
            } catch (e: Exception) {
                logger.error("Ошибка чистки сессий: ${e.message}", e)
                delay(TimeUnit.MINUTES.toMillis(30))
            }
        }
    }

    return parentJob
}
