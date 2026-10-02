package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.billing.PlayPurchaseVerifier
import com.lvsmsmch.aichat.billing.PurchaseCheck
import com.lvsmsmch.aichat.billing.SubscriptionRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Сверяет подписки с Google.
 *
 * Покупка подтверждается один раз, а живёт месяцами: человек отменяет её в
 * Play, у него перестаёт проходить карта, он возвращает деньги — и ни о чём
 * из этого Google нам сам не сообщает. Без этой сверки премиум, выданный
 * однажды, оставался бы навсегда.
 *
 * Сверка двусторонняя. Отозванную подписку забираем, но и возвращаем тоже:
 * после account hold или возобновления Google снова признаёт прежний токен,
 * и платящий человек не должен покупать второй раз.
 *
 * Права забираются ТОЛЬКО по явному «неактивна» от Google. Сетевая ошибка,
 * пятисотка на их стороне или ненастроенный ключ — повод пропустить круг, а
 * не повод оставить человека без оплаченного.
 */
fun configureSubscriptionsVerifyUpdater(
    databaseScope: CoroutineScope,
    subscriptionRepository: SubscriptionRepository,
    userRepository: UserRepository,
    purchaseVerifier: PlayPurchaseVerifier,
    intervalHours: Long = 6,
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                var revoked = 0
                var restored = 0
                var skipped = 0

                for (sub in subscriptionRepository.all()) {
                    val check = purchaseVerifier.verify(sub.purchaseToken)
                    // Google разрешает много, но долбить его в полную силу незачем
                    delay(200)

                    val active = when (check) {
                        PurchaseCheck.Active -> true
                        PurchaseCheck.Inactive -> false
                        // Проверять нечем или не получилось — состояние не трогаем
                        PurchaseCheck.NotConfigured -> {
                            skipped++
                            continue
                        }
                        is PurchaseCheck.Error -> {
                            logger.warn("Не удалось сверить подписку ${sub.userId}: ${check.message}")
                            skipped++
                            continue
                        }
                    }

                    if (active == sub.active) continue

                    subscriptionRepository.setActive(sub.userId, active)
                    userRepository.updateSubscriptionStatus(sub.userId, active)
                    if (active) restored++ else revoked++
                    logger.info("Подписка ${sub.userId}: ${if (active) "восстановлена" else "отозвана"}")
                }

                if (revoked > 0 || restored > 0 || skipped > 0) {
                    logger.info("Сверка подписок: отозвано $revoked, восстановлено $restored, пропущено $skipped")
                }
                delay(TimeUnit.HOURS.toMillis(intervalHours))
            } catch (e: CancellationException) {
                logger.debug("Subscriptions verify cancelled")
                break
            } catch (e: Exception) {
                logger.error("Ошибка сверки подписок: ${e.message}", e)
                delay(TimeUnit.MINUTES.toMillis(30))
            }
        }
    }

    return parentJob
}
