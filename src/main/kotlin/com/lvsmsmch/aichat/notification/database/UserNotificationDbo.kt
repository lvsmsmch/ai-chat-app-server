package com.lvsmsmch.aichat.notification.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

/** Типы событийных уведомлений. */
object NotificationTypes {
    /** На тебя подписались (не стакается, уходит и FCM-пушем). */
    const val FOLLOWER = "follower"

    /** Под твоим персонажем оставили коммент. */
    const val CHARACTER_COMMENT = "character_comment"

    /** Твой коммент лайкнули (стак: «набрал N лайков»). */
    const val COMMENT_LIKE = "comment_like"

    /** Юзер, на которого ты подписан, добавил персонажа (стак: «добавил N персонажей»). */
    const val FOLLOWEE_CHARACTER = "followee_character"

    /** Твой персонаж достиг 1 000 / 10 000 / 100 000 сообщений. */
    const val MILESTONE = "character_milestone"
}

/**
 * Событийное уведомление юзера. Стакающиеся типы ищутся по [stackKey]
 * среди непрочитанных: повтор события инкрементит [count] и поднимает
 * [updatedAt] (уведомление всплывает наверх ленты как непрочитанное).
 */
@Serializable
data class UserNotificationDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val userId: String,
    val type: String,
    val createdAt: String = UtcTimestamp.now().toString(),
    val updatedAt: String = UtcTimestamp.now().toString(),
    val isRead: Boolean = false,
    /** Кто совершил действие (подписался/лайкнул/прокомментил/добавил персонажа). */
    val actorUserId: String? = null,
    /** Персонаж события (мой — для комментов/вех; чужой новый — для followee). */
    val characterId: String? = null,
    /** Коммент события (новый коммент под персом / мой лайкнутый коммент). */
    val commentId: String? = null,
    /** Стак: сколько событий агрегировано (лайков, добавленных персонажей). */
    val count: Int = 1,
    /** Веха сообщений персонажа (1000/10000/100000). */
    val milestone: Int = 0,
    /** Ключ агрегации стакающихся типов; у вех — ключ дедупликации. */
    val stackKey: String? = null,
)
