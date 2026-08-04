package com.lvsmsmch.aichat.user.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toDeviceLimitCarryoverDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

/**
 * Анти-абьюз лимитов: снапшот потраченных счётчиков юзера по deviceId,
 * записывается при удалении аккаунта. Новый гостевой аккаунт на том же
 * устройстве наследует потраченные лимиты — «удалил-пересоздал» не обнуляет их.
 */
@Serializable
data class DeviceLimitCarryoverDbo(
    @BsonId val deviceId: String,
    val hourlyMessageCount: Int = 0,
    val dailyMessageCount: Int = 0,
    val monthlyMessageCount: Int = 0,
    val monthlyTopModelCount: Int = 0,
    val dailyImageCount: Int = 0,
    val monthlyTopImageCount: Int = 0,
    val savedAt: String = UtcTimestamp.now().toString(),
)

class DeviceLimitCarryoverRepository {

    /** Записать снапшот лимитов удаляемого аккаунта (последний перекрывает прошлый). */
    suspend fun save(user: UserDbo) {
        val deviceId = user.deviceId?.takeIf { it.isNotBlank() } ?: return
        val dbo = DeviceLimitCarryoverDbo(
            deviceId = deviceId,
            hourlyMessageCount = user.hourlyMessageCount,
            dailyMessageCount = user.dailyMessageCount,
            monthlyMessageCount = user.monthlyMessageCount,
            monthlyTopModelCount = user.monthlyTopModelCount,
            dailyImageCount = user.dailyImageCount,
            monthlyTopImageCount = user.monthlyTopImageCount,
        )
        dbQuery { Tables.DeviceLimitCarryovers.upsert { it.from(dbo) } }
    }

    /**
     * Забрать снапшот для нового гостя (запись удаляется). Протухшие части
     * обнуляются: дневные счётчики актуальны только в тот же UTC-день,
     * месячные — в тот же месяц.
     */
    suspend fun take(deviceId: String): DeviceLimitCarryoverDbo? {
        val dbo = dbQuery {
            val found = Tables.DeviceLimitCarryovers.selectAll()
                .where { Tables.DeviceLimitCarryovers.deviceId eq deviceId }
                .limit(1)
                .firstOrNull()
                ?.toDeviceLimitCarryoverDbo()
            if (found != null) {
                Tables.DeviceLimitCarryovers.deleteWhere {
                    Tables.DeviceLimitCarryovers.deviceId eq deviceId
                }
            }
            found
        } ?: return null

        val saved = runCatching { UtcTimestamp.parse(dbo.savedAt) }.getOrNull() ?: return null
        val now = UtcTimestamp.now()
        val sameDay = saved.toString().take(10) == now.toString().take(10)
        val sameMonth = saved.toString().take(7) == now.toString().take(7)
        return dbo.copy(
            hourlyMessageCount = if (sameDay) dbo.hourlyMessageCount else 0,
            dailyMessageCount = if (sameDay) dbo.dailyMessageCount else 0,
            dailyImageCount = if (sameDay) dbo.dailyImageCount else 0,
            monthlyMessageCount = if (sameMonth) dbo.monthlyMessageCount else 0,
            monthlyTopModelCount = if (sameMonth) dbo.monthlyTopModelCount else 0,
            monthlyTopImageCount = if (sameMonth) dbo.monthlyTopImageCount else 0,
        )
    }
}
