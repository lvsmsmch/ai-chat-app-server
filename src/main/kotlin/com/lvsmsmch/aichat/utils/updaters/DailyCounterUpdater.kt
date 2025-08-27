package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

fun configureDailyCountersUpdater(
    databaseScope: CoroutineScope,
    userRepository: UserRepository
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                val now = LocalDateTime.now()
                val nextDailyReset = now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
                val delayMillis = ChronoUnit.MILLIS.between(now, nextDailyReset)

                logger.info("Next daily counters reset scheduled at $nextDailyReset, waiting ${delayMillis}ms")
                delay(delayMillis)

                logger.info("Starting daily message counters reset")
                userRepository.resetDailyCountersForAllUsers()
                logger.info("Daily message counters reset completed")

            } catch (e: CancellationException) {
                logger.debug("Daily counters reset updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during daily counters reset: ${e.message}", e)
                delay(TimeUnit.MINUTES.toMillis(1))
            }
        }
    }

    return parentJob
}