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
                // Кандидаты на пуш «лимиты возобновились» — ДО обнуления счётчиков
                val pushCandidates = try {
                    userRepository.findLimitResetPushCandidates()
                } catch (e: Exception) {
                    logger.error("Failed to collect limit push candidates: ${e.message}")
                    emptyList()
                }
                userRepository.resetDailyCountersForAllUsers()
                logger.info("Daily message counters reset completed")
                sendLimitResetPushes(pushCandidates, userRepository)

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

/**
 * Пуш «лимиты возобновились» с затухающим каскадом, чтобы не доставать:
 * первый раз — сразу, второй — не раньше чем через 3 дня, дальше — раз в неделю.
 * Любая отправка двигает юзера на следующую ступень.
 */
private suspend fun sendLimitResetPushes(
    candidates: List<com.lvsmsmch.aichat.user.database.UserDbo>,
    userRepository: UserRepository,
) {
    var sent = 0
    for (user in candidates) {
        val token = user.fcmToken ?: continue
        val intervalDays = when (user.limitPushStage) {
            0 -> 0L
            1 -> 3L
            else -> 7L
        }
        val lastPush = user.lastLimitPushAt?.let { runCatching { com.lvsmsmch.aichat.utils.UtcTimestamp.parse(it) }.getOrNull() }
        val due = lastPush == null || lastPush.addDays(intervalDays).isInPast()
        if (!due) continue
        val ok = com.lvsmsmch.aichat.utils.FcmSender.send(
            token = token,
            title = "Your daily messages are back! ✨",
            body = "The limit has reset - your characters are waiting to chat.",
        )
        if (ok) {
            userRepository.markLimitPushSent(user.id, user.limitPushStage + 1)
            sent++
        }
    }
    if (candidates.isNotEmpty()) logger.info("Limit-reset pushes: $sent sent of ${candidates.size} candidates")
}