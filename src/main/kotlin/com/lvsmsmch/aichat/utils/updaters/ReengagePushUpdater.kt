package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.notification.pushStrings
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.FcmSender
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

/**
 * Напоминания «вернись в чат» (20.11). Пишет персонаж из последнего чата один
 * на один: заголовок — его имя, картинка — его ава, тап открывает этот чат.
 *
 * Ступени — через [REENGAGE_STEPS_DAYS] дней после последнего сообщения
 * (1, 3, 7). Дальше молчим до винбэка с подарком ([WINBACK_INACTIVE_DAYS]).
 * Человек снова написал — пауза кончилась, при следующей отсчёт с первой
 * ступени.
 *
 * Время — тот же час суток (UTC), в который он писал последний раз: без
 * часового пояса это лучшая догадка, когда ему удобно. Прогон раз в час.
 *
 * Чтобы не заваливать: между напоминаниями не меньше [MIN_GAP_HOURS] часов,
 * и не в тот же день, что пуш о лимите или винбэк. Пропущенную ступень
 * (сервер был выключен, или человек замолчал до запуска фичи) догоняем
 * один раз — самой поздней из подходящих, без серии подряд.
 */
val REENGAGE_STEPS_DAYS = listOf(1L, 3L, 7L)

/** Дольше этого молчат — уже забота винбэка, а не напоминаний. */
const val REENGAGE_MAX_INACTIVE_DAYS = 10L
private const val MIN_GAP_HOURS = 44L
private const val OTHER_PUSH_GAP_HOURS = 20L

fun configureReengagePushUpdater(
    databaseScope: CoroutineScope,
    userRepository: UserRepository,
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                // Начало следующего часа + 3 минуты
                val now = Instant.now()
                val next = now.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS).plus(3, ChronoUnit.MINUTES)
                delay(Duration.between(now, next).toMillis().coerceAtLeast(1_000))
                runReengagePushes(userRepository, Instant.now())
            } catch (e: CancellationException) {
                logger.debug("Reengage push updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during reengage push run: ${e.message}", e)
                delay(60_000)
            }
        }
    }

    return parentJob
}

private fun parseOrNull(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun hoursSince(iso: String?, now: Instant): Long? =
    parseOrNull(iso)?.let { Duration.between(it, now).toHours() }

internal suspend fun runReengagePushes(userRepository: UserRepository, now: Instant) {
    val candidates = userRepository.findReengageCandidates(
        activeAfterIso = now.minus(REENGAGE_MAX_INACTIVE_DAYS, ChronoUnit.DAYS).toString(),
        activeBeforeIso = now.minus(23, ChronoUnit.HOURS).toString(),
    )
    val hourNow = now.atOffset(ZoneOffset.UTC).hour
    var due = 0
    var sent = 0
    for (user in candidates) {
        val lastActive = parseOrNull(user.lastActiveAt) ?: continue
        if (lastActive.atOffset(ZoneOffset.UTC).hour != hourNow) continue
        val days = (Duration.between(lastActive, now).toMinutes() / 1440.0).roundToLong()
        val step = REENGAGE_STEPS_DAYS.indexOfLast { it <= days }
        if (step < 0) continue

        // Пуш был ДО последнего сообщения — это старая пауза, считаем заново
        val lastPush = parseOrNull(user.lastReengagePushAt)
        val sentInPause = if (lastPush == null || lastPush.isBefore(lastActive)) 0 else user.stage
        if (step < sentInPause) continue
        if (lastPush != null && !lastPush.isBefore(lastActive) &&
            Duration.between(lastPush, now).toHours() < MIN_GAP_HOURS
        ) continue
        if ((hoursSince(user.lastLimitPushAt, now) ?: Long.MAX_VALUE) < OTHER_PUSH_GAP_HOURS) continue
        if ((hoursSince(user.lastWinbackPushAt, now) ?: Long.MAX_VALUE) < OTHER_PUSH_GAP_HOURS) continue

        val chat = userRepository.findLastDirectChat(user.id) ?: continue
        due++
        val ok = FcmSender.send(
            token = user.fcmToken,
            title = chat.characterName,
            body = pushStrings(user.uiLanguage).reengageBody(step),
            route = "chat:${chat.chatId}:${chat.characterId}",
            imageUrl = chat.picUrl,
        )
        if (ok) {
            userRepository.markReengagePushSent(user.id, step + 1)
            sent++
        }
    }
    if (due > 0) logger.info("Reengage pushes: $sent sent of $due due (${candidates.size} in window)")
}
