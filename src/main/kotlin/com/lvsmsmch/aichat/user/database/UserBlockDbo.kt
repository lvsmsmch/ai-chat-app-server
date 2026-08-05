package com.lvsmsmch.aichat.user.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable

/**
 * Блокировка одного пользователя другим: [blockerId] больше не хочет видеть
 * ничего от [blockedId].
 *
 * Связь односторонняя и НЕ взаимная: заблокированный об этом не знает и своей
 * ленты не теряет — иначе блокировка превращалась бы в способ портить чужой
 * экран.
 */
@Serializable
data class UserBlockDbo(
    val id: String = "",
    val blockedAt: String = UtcTimestamp.now().toString(),
    val blockerId: String,
    val blockedId: String,
)
