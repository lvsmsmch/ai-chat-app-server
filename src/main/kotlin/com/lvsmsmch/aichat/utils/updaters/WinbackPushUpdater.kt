package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.FcmSender
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Винбэк: юзерам, которые написали больше [WINBACK_MIN_MESSAGES] сообщений,
 * но не заходили [WINBACK_INACTIVE_DAYS] дней, дарим [WINBACK_GIFT_MESSAGES]
 * экстра-сообщений и шлём пуш. Не чаще раза в [WINBACK_COOLDOWN_DAYS] дней.
 * Проверка раз в 6 часов.
 */
const val WINBACK_MIN_MESSAGES = 5
const val WINBACK_INACTIVE_DAYS = 7L
const val WINBACK_COOLDOWN_DAYS = 30L
const val WINBACK_GIFT_MESSAGES = 100

fun configureWinbackPushUpdater(
    databaseScope: CoroutineScope,
    userRepository: UserRepository
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                delay(TimeUnit.HOURS.toMillis(6))

                val now = UtcTimestamp.now()
                val candidates = userRepository.findWinbackCandidates(
                    inactiveSinceIso = now.subtractDays(WINBACK_INACTIVE_DAYS).toString(),
                    lastPushBeforeIso = now.subtractDays(WINBACK_COOLDOWN_DAYS).toString(),
                    minMessages = WINBACK_MIN_MESSAGES,
                )
                var sent = 0
                for (user in candidates) {
                    val token = user.fcmToken ?: continue
                    val ok = FcmSender.send(
                        token = token,
                        title = "A gift is waiting for you! 🎁",
                        body = "+$WINBACK_GIFT_MESSAGES free messages. Tap to claim your gift!",
                    )
                    if (ok) {
                        userRepository.grantWinbackGift(user.id, WINBACK_GIFT_MESSAGES)
                        sent++
                    }
                }
                if (candidates.isNotEmpty()) {
                    logger.info("Winback pushes: $sent sent of ${candidates.size} candidates")
                }
            } catch (e: CancellationException) {
                logger.debug("Winback push updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during winback push run: ${e.message}", e)
                delay(TimeUnit.MINUTES.toMillis(5))
            }
        }
    }

    return parentJob
}
