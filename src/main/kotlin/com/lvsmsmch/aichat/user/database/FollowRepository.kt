package com.lvsmsmch.aichat.user.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toFollowDbo
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll

class FollowRepository {

    suspend fun addConnection(session: DbSession, followerId: String, followeeId: String) {
        if (followerId == followeeId) {
            throw IllegalArgumentException("User cannot follow themselves")
        }
        dbQuery {
            val exists = Tables.Follows.selectAll()
                .where {
                    (Tables.Follows.followerId eq followerId) and
                        (Tables.Follows.followeeId eq followeeId)
                }
                .limit(1)
                .any()
            if (!exists) {
                Tables.Follows.insert {
                    it.from(
                        FollowDbo(
                            id = "${followerId}_${followeeId}",
                            followerId = followerId,
                            followeeId = followeeId,
                        )
                    )
                }
            }
        }
    }

    suspend fun getFollowers(userId: String, beforeTime: UtcTimestamp?, size: Int): List<FollowDbo> =
        dbQuery {
            Tables.Follows.selectAll()
                .where {
                    val base = Tables.Follows.followeeId eq userId
                    if (beforeTime == null) base
                    else base and (Tables.Follows.followedAt less beforeTime.toString())
                }
                .orderBy(Tables.Follows.followedAt to SortOrder.DESC)
                .limit(size)
                .map { it.toFollowDbo() }
        }

    suspend fun getFollowings(userId: String, beforeTime: UtcTimestamp?, size: Int): List<FollowDbo> =
        dbQuery {
            Tables.Follows.selectAll()
                .where {
                    val base = Tables.Follows.followerId eq userId
                    if (beforeTime == null) base
                    else base and (Tables.Follows.followedAt less beforeTime.toString())
                }
                .orderBy(Tables.Follows.followedAt to SortOrder.DESC)
                .limit(size)
                .map { it.toFollowDbo() }
        }

    /** Без транзакции — для рассылки уведомлений подписчикам. */
    suspend fun getAllFollowerIds(userId: String): List<String> = dbQuery {
        Tables.Follows.selectAll()
            .where { Tables.Follows.followeeId eq userId }
            .map { it[Tables.Follows.followerId] }
    }

    suspend fun getAllFollowerIds(session: DbSession, userId: String): List<String> =
        getAllFollowerIds(userId)

    suspend fun getAllFollowingIds(session: DbSession, userId: String): List<String> = dbQuery {
        Tables.Follows.selectAll()
            .where { Tables.Follows.followerId eq userId }
            .map { it[Tables.Follows.followeeId] }
    }

    suspend fun countFollowers(userId: String): Int = dbQuery {
        Tables.Follows.selectAll().where { Tables.Follows.followeeId eq userId }.count().toInt()
    }

    suspend fun countFollowings(userId: String): Int = dbQuery {
        Tables.Follows.selectAll().where { Tables.Follows.followerId eq userId }.count().toInt()
    }

    /**
     * Из [followeeIds] возвращает те, на кого [followerId] реально подписан.
     * Нужно спискам followers/following: кнопка в строке показывает
     * настоящее состояние, а не предположение клиента.
     */
    suspend fun getFollowedIds(followerId: String, followeeIds: List<String>): Set<String> {
        if (followeeIds.isEmpty()) return emptySet()
        return dbQuery {
            Tables.Follows.selectAll()
                .where {
                    (Tables.Follows.followerId eq followerId) and
                        (Tables.Follows.followeeId inList followeeIds)
                }
                .map { it[Tables.Follows.followeeId] }
                .toSet()
        }
    }

    suspend fun doesConnectionExist(followerId: String, followeeId: String): Boolean = dbQuery {
        Tables.Follows.selectAll()
            .where {
                (Tables.Follows.followerId eq followerId) and
                    (Tables.Follows.followeeId eq followeeId)
            }
            .limit(1)
            .any()
    }

    suspend fun removeConnection(session: DbSession, followerId: String, followeeId: String) {
        dbQuery {
            Tables.Follows.deleteWhere {
                (Tables.Follows.followerId eq followerId) and
                    (Tables.Follows.followeeId eq followeeId)
            }
        }
    }

    suspend fun removeAllConnectionsContainingUserId(session: DbSession, userId: String) {
        dbQuery {
            Tables.Follows.deleteWhere {
                (Tables.Follows.followerId eq userId) or (Tables.Follows.followeeId eq userId)
            }
        }
    }
}
