package com.lvsmsmch.aichat.user.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toUserBlockDbo
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll

/**
 * Кто кого заблокировал.
 *
 * Id строки — сама пара `blocker_blocked`, а не случайный: повторная
 * блокировка тогда невозможна в принципе, без предварительной проверки
 * «а не заблокирован ли уже».
 */
class UserBlockRepository {

    suspend fun block(blockerId: String, blockedId: String) {
        if (blockerId == blockedId) throw IllegalArgumentException("User cannot block themselves")
        dbQuery {
            val exists = Tables.UserBlocks.selectAll()
                .where {
                    (Tables.UserBlocks.blockerId eq blockerId) and
                        (Tables.UserBlocks.blockedId eq blockedId)
                }
                .limit(1)
                .any()
            if (!exists) {
                Tables.UserBlocks.insert {
                    it.from(
                        UserBlockDbo(
                            id = "${blockerId}_$blockedId",
                            blockerId = blockerId,
                            blockedId = blockedId,
                        )
                    )
                }
            }
        }
    }

    suspend fun unblock(blockerId: String, blockedId: String) {
        dbQuery {
            Tables.UserBlocks.deleteWhere {
                (Tables.UserBlocks.blockerId eq blockerId) and
                    (Tables.UserBlocks.blockedId eq blockedId)
            }
        }
    }

    suspend fun isBlocked(blockerId: String, blockedId: String): Boolean = dbQuery {
        Tables.UserBlocks.selectAll()
            .where {
                (Tables.UserBlocks.blockerId eq blockerId) and
                    (Tables.UserBlocks.blockedId eq blockedId)
            }
            .limit(1)
            .any()
    }

    /**
     * Кого скрывать от [blockerId]. Запрашивается на каждый список персонажей,
     * поэтому отдаём множеством: дальше проверка идёт по памяти, а не запросом
     * на каждую плитку.
     */
    suspend fun getBlockedIds(blockerId: String): Set<String> = dbQuery {
        Tables.UserBlocks.selectAll()
            .where { Tables.UserBlocks.blockerId eq blockerId }
            .map { it[Tables.UserBlocks.blockedId] }
            .toSet()
    }

    /** Список для экрана «Blocked users»: свежие блокировки сверху. */
    suspend fun getBlocked(blockerId: String): List<UserBlockDbo> = dbQuery {
        Tables.UserBlocks.selectAll()
            .where { Tables.UserBlocks.blockerId eq blockerId }
            .orderBy(Tables.UserBlocks.blockedAt to SortOrder.DESC)
            .map { it.toUserBlockDbo() }
    }

    /** Удаление аккаунта: чужие блокировки на него тоже теряют смысл. */
    suspend fun removeAllContaining(userId: String) {
        dbQuery {
            Tables.UserBlocks.deleteWhere {
                (Tables.UserBlocks.blockerId eq userId) or (Tables.UserBlocks.blockedId eq userId)
            }
        }
    }
}
