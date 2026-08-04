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

    data class Lockout(val failedCount: Int, val lockedUntil: String?)

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
            ?.let { Lockout(it[table.failedCount], it[table.lockedUntil]) }
    }

    /** Неудачная попытка входа: счётчик растёт, пауза удлиняется. */
    suspend fun registerFailure(loginKey: String) {
        val now = UtcTimestamp.now()
        val current = get(loginKey)
        val nextCount = (current?.failedCount ?: 0) + 1
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
     * пауза растёт до получаса и в пределе до двенадцати часов.
     */
    private fun lockMinutesFor(failedCount: Int): Long = when (failedCount) {
        in 0..3 -> 0
        in 4..5 -> 1
        in 6..8 -> 5
        in 9..12 -> 30
        in 13..16 -> 180
        in 17..20 -> 360
        else -> 720
    }
}
