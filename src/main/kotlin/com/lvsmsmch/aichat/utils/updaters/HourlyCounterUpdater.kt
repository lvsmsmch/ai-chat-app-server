package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

fun configureHourlyCountersUpdater(
    databaseScope: CoroutineScope,
    userRepository: UserRepository
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                val now = LocalDateTime.now()
                val nextHourlyReset = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
                val delayMillis = ChronoUnit.MILLIS.between(now, nextHourlyReset)
                
                logger.info("Next hourly counters reset scheduled at $nextHourlyReset, waiting ${delayMillis}ms")
                delay(delayMillis)
                
                logger.info("Starting hourly message counters reset")
                userRepository.resetHourlyCountersForAllUsers()
                logger.info("Hourly message counters reset completed")
                
            } catch (e: CancellationException) {
                logger.debug("Hourly counters reset updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during hourly counters reset: ${e.message}", e)
                delay(TimeUnit.MINUTES.toMillis(1))
            }
        }
    }

    return parentJob
}