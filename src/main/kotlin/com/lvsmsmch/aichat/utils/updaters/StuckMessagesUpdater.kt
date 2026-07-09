package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Marks STREAMING messages that stopped receiving updates as FAILED.
 * This recovers messages orphaned by a server restart or a silently died
 * generation job, so clients don't wait for them forever.
 */
fun configureStuckMessagesUpdater(
    databaseScope: CoroutineScope,
    messageRepository: MessageRepository,
    checkIntervalSeconds: Long = 60,
    stuckThresholdMinutes: Long = 2
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                val stuckSince = UtcTimestamp.now().subtractMinutes(stuckThresholdMinutes)
                val failedCount = messageRepository.failStuckStreamingMessages(stuckSince)

                if (failedCount > 0) {
                    logger.warn("Marked $failedCount stuck streaming message(s) as failed")
                }

                delay(TimeUnit.SECONDS.toMillis(checkIntervalSeconds))
            } catch (e: CancellationException) {
                logger.debug("Stuck messages updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during stuck messages check: ${e.message}", e)
                delay(TimeUnit.MINUTES.toMillis(1))
            }
        }
    }

    return parentJob
}
