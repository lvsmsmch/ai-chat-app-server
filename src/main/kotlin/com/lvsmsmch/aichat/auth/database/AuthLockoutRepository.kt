package com.lvsmsmch.aichat.auth.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

/**
 * Защита от перебора пароля по конкретному аккаунту.
 *
 * Лимита запросов по IP (rate-limit Ktor) недостаточно: перебор идёт с многих
 * адресов, и по каждому лимит не выбирается. Поэтому счётчик привязан к почте,
 * а пауза растёт с числом неудач — первые опечатки почти не мешают, а
 * систематический подбор быстро становится бессмысленным по времени.
 *
 * Ключ — почта в нижнем регистре, и он НЕ говорит, существует ли такой
 * аккаунт: счётчик ведётся даже для неизвестных адресов, иначе разница в
 * поведении сама выдавала бы, какие адреса зарегистрированы.
 */
class AuthLockoutRepository {

    private val table = Tables.AuthLockouts

    data class Lockout(val failedCount: Int, val lockedUntil: String?, val lastFailedAt: String?)

    /** Сколько секунд ещё ждать; 0 — можно пробовать. */
    suspend fun secondsRemaining(loginKey: String): Long {
        val state = get(loginKey) ?: return 0
        val until = state.lockedUntil ?: return 0
        val parsed = runCatching { UtcTimestamp.parse(until) }.getOrNull() ?: return 0
        if (parsed.isInPast()) return 0
        return java.time.Duration.between(java.time.Instant.now(), parsed.instant).seconds
            .coerceAtLeast(1)
    }

    private suspend fun get(loginKey: String): Lockout? = dbQuery {
        table.selectAll()
            .where { table.loginKey eq loginKey }
            .limit(1)
            .firstOrNull()
            ?.let { Lockout(it[table.failedCount], it[table.lockedUntil], it[table.lastFailedAt]) }
    }

    /** Неудачная попытка входа: счётчик растёт, пауза удлиняется. */
    suspend fun registerFailure(loginKey: String) {
        val now = UtcTimestamp.now()
        val current = get(loginKey)
        // Счётчик забывается, если промахов давно не было: иначе полсотни
        // опечаток за год оставляли бы человека с 12-часовой паузой навсегда
        val stale = current?.lastFailedAt
            ?.let { runCatching { UtcTimestamp.parse(it) }.getOrNull() }
            ?.let {
                java.time.Duration.between(it.instant, java.time.Instant.now()).toHours() >=
                    FAILURE_MEMORY_HOURS
            }
            ?: true
        val nextCount = if (stale) 1 else (current?.failedCount ?: 0) + 1
        val lockMinutes = lockMinutesFor(nextCount)
        dbQuery {
            table.upsert {
                it[table.loginKey] = loginKey
                it[table.failedCount] = nextCount
                it[table.lockedUntil] =
                    if (lockMinutes == 0L) null else now.addMinutes(lockMinutes).toString()
                it[table.lastFailedAt] = now.toString()
            }
        }
    }

    /** Успешный вход — история неудач стирается. */
    suspend fun clear(loginKey: String) {
        dbQuery { table.deleteWhere { table.loginKey eq loginKey } }
    }

    /**
     * Первые три промаха не наказываются вообще: это обычные опечатки. Дальше
     * пауза растёт, но упирается в полчаса.
     *
     * Потолок намеренно низкий. Ключ здесь — чужая почта, поэтому длинная
     * блокировка работает и в обратную сторону: посторонний закрыл бы человеку
     * вход на полсуток, просто отправив два десятка неверных паролей. Подбор
     * пароля получасовые паузы останавливают не хуже: bcrypt со стоимостью 12
     * и сам по себе делает перебор безнадёжным.
     */
    private fun lockMinutesFor(failedCount: Int): Long = when (failedCount) {
        in 0..3 -> 0
        in 4..5 -> 1
        in 6..8 -> 5
        in 9..12 -> 15
        else -> 30
    }

    private companion object {
        /** Через столько часов без промахов счётчик начинается заново. */
        const val FAILURE_MEMORY_HOURS = 12L
    }
}
