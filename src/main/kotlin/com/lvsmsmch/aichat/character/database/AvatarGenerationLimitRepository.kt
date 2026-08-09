package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

data class AvatarGenerationQuota(
    val allowed: Boolean,
    val hourlyUsed: Int,
    val hourlyLimit: Int,
    val dailyUsed: Int,
    val dailyLimit: Int,
    val hourlyResetAfterSeconds: Long,
    val dailyResetAfterSeconds: Long,
    val limitReason: String? = null,
    internal val hourlyWindowStartedAt: Long,
    internal val dailyWindowStartedAt: Long,
)

/** Персистентные rolling-window лимиты AI-аватаров с блокировкой строки на резерв. */
class AvatarGenerationLimitRepository {

    companion object {
        const val HOURLY_LIMIT = 15
        const val DAILY_LIMIT = 30
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val DAY_MS = 24L * HOUR_MS
    }

    suspend fun current(userId: String): AvatarGenerationQuota = access(userId, reserve = false)

    suspend fun reserve(userId: String): AvatarGenerationQuota = access(userId, reserve = true)

    private suspend fun access(userId: String, reserve: Boolean): AvatarGenerationQuota = dbQuery {
        val table = Tables.AvatarGenerationLimits
        val now = System.currentTimeMillis()
        table.insertIgnore {
            it[table.userId] = userId
            it[hourlyWindowStartedAt] = now
            it[hourlyCount] = 0
            it[dailyWindowStartedAt] = now
            it[dailyCount] = 0
        }
        val row = table.selectAll().where { table.userId eq userId }.forUpdate().single()
        var hourlyStart = row[table.hourlyWindowStartedAt]
        var dailyStart = row[table.dailyWindowStartedAt]
        var hourlyCount = row[table.hourlyCount]
        var dailyCount = row[table.dailyCount]

        if (now - hourlyStart >= HOUR_MS) {
            hourlyStart = now
            hourlyCount = 0
        }
        if (now - dailyStart >= DAY_MS) {
            dailyStart = now
            dailyCount = 0
        }

        val limitReason = when {
            hourlyCount >= HOURLY_LIMIT -> "hourly"
            dailyCount >= DAILY_LIMIT -> "daily"
            else -> null
        }
        val allowed = limitReason == null
        if (reserve && allowed) {
            hourlyCount++
            dailyCount++
        }

        table.update({ table.userId eq userId }) {
            it[hourlyWindowStartedAt] = hourlyStart
            it[table.hourlyCount] = hourlyCount
            it[dailyWindowStartedAt] = dailyStart
            it[table.dailyCount] = dailyCount
        }

        quota(
            allowed = allowed,
            hourlyCount = hourlyCount,
            dailyCount = dailyCount,
            hourlyStart = hourlyStart,
            dailyStart = dailyStart,
            now = now,
            limitReason = limitReason,
        )
    }

    /** Неудачный запрос к провайдеру не расходует резерв, если окно ещё то же. */
    suspend fun release(userId: String, reservation: AvatarGenerationQuota) = dbQuery {
        val table = Tables.AvatarGenerationLimits
        val row = table.selectAll().where { table.userId eq userId }.forUpdate().singleOrNull()
            ?: return@dbQuery
        val sameHour = row[table.hourlyWindowStartedAt] == reservation.hourlyWindowStartedAt
        val sameDay = row[table.dailyWindowStartedAt] == reservation.dailyWindowStartedAt
        table.update({ table.userId eq userId }) {
            if (sameHour) {
                it[hourlyCount] = (row[table.hourlyCount] - 1).coerceAtLeast(0)
            }
            if (sameDay) {
                it[dailyCount] = (row[table.dailyCount] - 1).coerceAtLeast(0)
            }
        }
    }

    private fun quota(
        allowed: Boolean,
        hourlyCount: Int,
        dailyCount: Int,
        hourlyStart: Long,
        dailyStart: Long,
        now: Long,
        limitReason: String?,
    ) = AvatarGenerationQuota(
        allowed = allowed,
        hourlyUsed = hourlyCount,
        hourlyLimit = HOURLY_LIMIT,
        dailyUsed = dailyCount,
        dailyLimit = DAILY_LIMIT,
        hourlyResetAfterSeconds = ((hourlyStart + HOUR_MS - now).coerceAtLeast(0) + 999) / 1000,
        dailyResetAfterSeconds = ((dailyStart + DAY_MS - now).coerceAtLeast(0) + 999) / 1000,
        limitReason = limitReason,
        hourlyWindowStartedAt = hourlyStart,
        dailyWindowStartedAt = dailyStart,
    )
}
