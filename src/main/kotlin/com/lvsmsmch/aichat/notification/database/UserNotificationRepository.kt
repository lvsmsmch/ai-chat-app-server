package com.lvsmsmch.aichat.notification.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toUserNotificationDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class UserNotificationRepository {

    /** Уведомления юзера — удаляются вместе с аккаунтом. */
    suspend fun deleteAllForUser(userId: String) {
        dbQuery { Tables.UserNotifications.deleteWhere { Tables.UserNotifications.userId eq userId } }
    }

    suspend fun insert(dbo: UserNotificationDbo) {
        dbQuery { Tables.UserNotifications.insert { it.from(dbo) } }
    }

    /**
     * Стакающееся уведомление: непрочитанное с тем же [UserNotificationDbo.stackKey]
     * инкрементится и всплывает наверх; нет такого — создаётся новое.
     * Прочитанный стак не трогается: новая серия событий = новое уведомление.
     */
    suspend fun upsertStacked(fresh: UserNotificationDbo) {
        dbQuery {
            val existingId = Tables.UserNotifications.selectAll()
                .where {
                    (Tables.UserNotifications.stackKey eq fresh.stackKey) and
                        (Tables.UserNotifications.isRead eq false)
                }
                .limit(1)
                .firstOrNull()
                ?.get(Tables.UserNotifications.id)

            if (existingId == null) {
                Tables.UserNotifications.insert { it.from(fresh) }
            } else {
                Tables.UserNotifications.update({ Tables.UserNotifications.id eq existingId }) {
                    it[Tables.UserNotifications.count] = Tables.UserNotifications.count plus 1
                    it[Tables.UserNotifications.updatedAt] = UtcTimestamp.now().toString()
                    // Актор обновляется на последнего — «X и ещё N» на клиенте
                    it[Tables.UserNotifications.actorUserId] = fresh.actorUserId
                }
            }
        }
    }

    /** Веха уже зафиксирована? (дедуп по stackKey). */
    suspend fun existsByStackKey(stackKey: String): Boolean = dbQuery {
        Tables.UserNotifications.selectAll()
            .where { Tables.UserNotifications.stackKey eq stackKey }
            .limit(1)
            .any()
    }

    /** Лента: новые (по updatedAt) сверху, курсорная пагинация. */
    suspend fun list(
        userId: String,
        beforeUpdatedAt: String?,
        size: Int,
    ): List<UserNotificationDbo> = dbQuery {
        Tables.UserNotifications.selectAll()
            .where {
                val base = Tables.UserNotifications.userId eq userId
                if (beforeUpdatedAt == null) base
                else base and (Tables.UserNotifications.updatedAt less beforeUpdatedAt)
            }
            .orderBy(Tables.UserNotifications.updatedAt to SortOrder.DESC)
            .limit(size)
            .map { it.toUserNotificationDbo() }
    }

    suspend fun unreadCount(userId: String): Long = dbQuery {
        Tables.UserNotifications.selectAll()
            .where {
                (Tables.UserNotifications.userId eq userId) and
                    (Tables.UserNotifications.isRead eq false)
            }
            .count()
    }

    suspend fun markRead(userId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        dbQuery {
            Tables.UserNotifications.update({
                (Tables.UserNotifications.userId eq userId) and
                    (Tables.UserNotifications.id inList ids)
            }) {
                it[Tables.UserNotifications.isRead] = true
            }
        }
    }

    suspend fun markAllRead(userId: String) {
        dbQuery {
            Tables.UserNotifications.update({
                (Tables.UserNotifications.userId eq userId) and
                    (Tables.UserNotifications.isRead eq false)
            }) {
                it[Tables.UserNotifications.isRead] = true
            }
        }
    }
}
