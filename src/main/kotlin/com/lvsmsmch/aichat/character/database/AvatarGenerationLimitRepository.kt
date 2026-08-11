package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
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

data class AvatarGenerationAttempt(
    val requestId: String,
    val status: String,
    val imageUrl: String? = null,
    val shouldGenerate: Boolean = false,
    val quota: AvatarGenerationQuota,
)

data class AvatarGenerationResult(
    val requestId: String,
    val status: String,
    val imageUrl: String? = null,
)

/** Персистентные rolling-window лимиты AI-аватаров с блокировкой строки на резерв. */
class AvatarGenerationLimitRepository {

    companion object {
        const val HOURLY_LIMIT = 10
        const val DAILY_LIMIT = 20
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val DAY_MS = 24L * HOUR_MS
        private const val STALE_REQUEST_MS = 10L * 60L * 1000L
    }

    suspend fun current(userId: String): AvatarGenerationQuota = access(userId, reserve = false)

    /**
     * Резервирует лимит ровно один раз на requestId. Пока предыдущая генерация
     * выполняется, повторный запрос получает её же id и не запускает провайдера
     * второй раз. Это позволяет клиенту безопасно восстановиться после фона.
     */
    suspend fun reserve(userId: String, requestId: String): AvatarGenerationAttempt = dbQuery {
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

        val storedRequestId = row[table.generationRequestId]
        val storedStatus = row[table.generationStatus]
        val storedStartedAt = row[table.generationStartedAt]
        val pendingIsFresh = storedStatus == "pending" && storedStartedAt != null &&
            now - storedStartedAt < STALE_REQUEST_MS

        // Тот же запрос или ещё живая генерация этого пользователя: возвращаем
        // сохранённое состояние, ничего не списывая и не запуская повторно.
        if (storedRequestId == requestId || (storedRequestId != null && pendingIsFresh)) {
            table.update({ table.userId eq userId }) {
                it[hourlyWindowStartedAt] = hourlyStart
                it[table.hourlyCount] = hourlyCount
                it[dailyWindowStartedAt] = dailyStart
                it[table.dailyCount] = dailyCount
            }
            return@dbQuery AvatarGenerationAttempt(
                requestId = storedRequestId ?: requestId,
                status = storedStatus ?: "failed",
                imageUrl = row[table.generatedImageUrl],
                quota = quota(
                    allowed = true,
                    hourlyCount = hourlyCount,
                    dailyCount = dailyCount,
                    hourlyStart = hourlyStart,
                    dailyStart = dailyStart,
                    now = now,
                    limitReason = null,
                ),
            )
        }

        // Незавершённый запрос старше десяти минут уже не может ожить. Возвращаем
        // его резерв перед созданием нового запроса.
        if (storedStatus == "pending") {
            hourlyCount = (hourlyCount - 1).coerceAtLeast(0)
            dailyCount = (dailyCount - 1).coerceAtLeast(0)
        }

        val limitReason = when {
            hourlyCount >= HOURLY_LIMIT -> "hourly"
            dailyCount >= DAILY_LIMIT -> "daily"
            else -> null
        }
        if (limitReason != null) {
            val limitedQuota = quota(
                allowed = false,
                hourlyCount = hourlyCount,
                dailyCount = dailyCount,
                hourlyStart = hourlyStart,
                dailyStart = dailyStart,
                now = now,
                limitReason = limitReason,
            )
            table.update({ table.userId eq userId }) {
                it[hourlyWindowStartedAt] = hourlyStart
                it[table.hourlyCount] = hourlyCount
                it[dailyWindowStartedAt] = dailyStart
                it[table.dailyCount] = dailyCount
            }
            return@dbQuery AvatarGenerationAttempt(
                requestId = requestId,
                status = "limited",
                quota = limitedQuota,
            )
        }

        hourlyCount++
        dailyCount++
        table.update({ table.userId eq userId }) {
            it[hourlyWindowStartedAt] = hourlyStart
            it[table.hourlyCount] = hourlyCount
            it[dailyWindowStartedAt] = dailyStart
            it[table.dailyCount] = dailyCount
            it[generationRequestId] = requestId
            it[generationStatus] = "pending"
            it[generatedImageUrl] = null
            it[generationStartedAt] = now
        }
        AvatarGenerationAttempt(
            requestId = requestId,
            status = "pending",
            shouldGenerate = true,
            quota = quota(
                allowed = true,
                hourlyCount = hourlyCount,
                dailyCount = dailyCount,
                hourlyStart = hourlyStart,
                dailyStart = dailyStart,
                now = now,
                limitReason = null,
            ),
        )
    }

    suspend fun result(userId: String, requestId: String): AvatarGenerationResult? = dbQuery {
        val table = Tables.AvatarGenerationLimits
        table.selectAll()
            .where { (table.userId eq userId) and (table.generationRequestId eq requestId) }
            .singleOrNull()
            ?.let {
                AvatarGenerationResult(
                    requestId = requestId,
                    status = it[table.generationStatus] ?: "failed",
                    imageUrl = it[table.generatedImageUrl],
                )
            }
    }

    suspend fun complete(userId: String, requestId: String, imageUrl: String) = dbQuery {
        val table = Tables.AvatarGenerationLimits
        table.update({ (table.userId eq userId) and (table.generationRequestId eq requestId) }) {
            it[generationStatus] = "completed"
            it[generatedImageUrl] = imageUrl
        }
    }

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
    suspend fun fail(
        userId: String,
        requestId: String,
        reservation: AvatarGenerationQuota,
    ) = dbQuery {
        val table = Tables.AvatarGenerationLimits
        val row = table.selectAll().where { table.userId eq userId }.forUpdate().singleOrNull()
            ?: return@dbQuery
        if (row[table.generationRequestId] != requestId || row[table.generationStatus] != "pending") {
            return@dbQuery
        }
        val sameHour = row[table.hourlyWindowStartedAt] == reservation.hourlyWindowStartedAt
        val sameDay = row[table.dailyWindowStartedAt] == reservation.dailyWindowStartedAt
        table.update({ table.userId eq userId }) {
            if (sameHour) {
                it[hourlyCount] = (row[table.hourlyCount] - 1).coerceAtLeast(0)
            }
            if (sameDay) {
                it[dailyCount] = (row[table.dailyCount] - 1).coerceAtLeast(0)
            }
            it[generationStatus] = "failed"
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
