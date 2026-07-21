package com.lvsmsmch.aichat.notification.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

class UserNotificationRepository(
    private val collection: CoroutineCollection<UserNotificationDbo>
) {

    suspend fun ensureIndexes() {
        collection.ensureIndex(ascending(UserNotificationDbo::userId, UserNotificationDbo::updatedAt))
        collection.ensureIndex(ascending(UserNotificationDbo::userId, UserNotificationDbo::isRead))
        collection.ensureIndex(ascending(UserNotificationDbo::stackKey))
    }

    suspend fun insert(dbo: UserNotificationDbo) {
        collection.insertOne(dbo)
    }

    /**
     * Стакающееся уведомление: непрочитанное с тем же [UserNotificationDbo.stackKey]
     * инкрементится и всплывает наверх; нет такого — создаётся новое.
     * Прочитанный стак не трогается: новая серия событий = новое уведомление.
     */
    suspend fun upsertStacked(fresh: UserNotificationDbo) {
        val existing = collection.findOne(
            and(
                UserNotificationDbo::stackKey eq fresh.stackKey,
                UserNotificationDbo::isRead eq false,
            )
        )
        if (existing == null) {
            collection.insertOne(fresh)
        } else {
            collection.updateOneById(
                existing.id,
                combine(
                    inc(UserNotificationDbo::count, 1),
                    setValue(UserNotificationDbo::updatedAt, UtcTimestamp.now().toString()),
                    // Актор обновляется на последнего — «X и ещё N» на клиенте
                    setValue(UserNotificationDbo::actorUserId, fresh.actorUserId),
                ),
            )
        }
    }

    /** Веха уже зафиксирована? (дедуп по stackKey). */
    suspend fun existsByStackKey(stackKey: String): Boolean =
        collection.countDocuments(UserNotificationDbo::stackKey eq stackKey) > 0

    /** Лента: новые (по updatedAt) сверху, курсорная пагинация. */
    suspend fun list(userId: String, beforeUpdatedAt: String?, size: Int): List<UserNotificationDbo> {
        val filter = if (beforeUpdatedAt == null) {
            UserNotificationDbo::userId eq userId
        } else {
            and(
                UserNotificationDbo::userId eq userId,
                UserNotificationDbo::updatedAt lt beforeUpdatedAt,
            )
        }
        return collection.find(filter)
            .sort(descending(UserNotificationDbo::updatedAt))
            .limit(size)
            .toList()
    }

    suspend fun unreadCount(userId: String): Long =
        collection.countDocuments(
            and(UserNotificationDbo::userId eq userId, UserNotificationDbo::isRead eq false)
        )

    suspend fun markRead(userId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        collection.updateMany(
            and(UserNotificationDbo::userId eq userId, UserNotificationDbo::id `in` ids),
            setValue(UserNotificationDbo::isRead, true),
        )
    }

    suspend fun markAllRead(userId: String) {
        collection.updateMany(
            and(UserNotificationDbo::userId eq userId, UserNotificationDbo::isRead eq false),
            setValue(UserNotificationDbo::isRead, true),
        )
    }
}
