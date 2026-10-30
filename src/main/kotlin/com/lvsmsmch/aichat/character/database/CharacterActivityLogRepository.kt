package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

@Serializable
data class CharacterActivityLogDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val timestamp: String = UtcTimestamp.now().toString(),
    val characterId: String,
    val activityType: Int,
    val userId: String,
)

enum class ActivityType(val code: Int) {
    CHAT_CREATED(0),
    MESSAGE_SENT(1),
    REVIEW_ADDED(2),
    COMMENT_ADDED(3),
}

class CharacterActivityLogRepository {

    suspend fun logActivity(
        session: DbSession,
        activityType: ActivityType,
        characterId: String,
        userId: String,
    ) {
        val log = CharacterActivityLogDbo(
            characterId = characterId,
            activityType = activityType.code,
            userId = userId,
        )
        dbQuery { Tables.CharacterActivityLogs.insert { it.from(log) } }
    }

    suspend fun getActivity(
        activityType: ActivityType,
        characterId: String,
        since: UtcTimestamp,
    ): Int = dbQuery {
        Tables.CharacterActivityLogs.selectAll()
            .where {
                (Tables.CharacterActivityLogs.characterId eq characterId) and
                    (Tables.CharacterActivityLogs.activityType eq activityType.code) and
                    (Tables.CharacterActivityLogs.timestamp greaterEq since.toString())
            }
            .count()
            .toInt()
    }

    /**
     * Сколько РАЗНЫХ юзеров совершили действие. В Mongo это была агрегация
     * group + count, в SQL — обычный COUNT(DISTINCT).
     */
    suspend fun getUniqueUsersForActivity(
        activityType: ActivityType,
        characterId: String,
        since: UtcTimestamp,
    ): Int = dbQuery {
        val distinctUsers = Tables.CharacterActivityLogs.userId.countDistinct()
        Tables.CharacterActivityLogs
            .select(distinctUsers)
            .where {
                (Tables.CharacterActivityLogs.characterId eq characterId) and
                    (Tables.CharacterActivityLogs.activityType eq activityType.code) and
                    (Tables.CharacterActivityLogs.timestamp greaterEq since.toString())
            }
            .firstOrNull()
            ?.get(distinctUsers)
            ?.toInt()
            ?: 0
    }

    /**
     * Сколько сообщений каждый юзер написал каждому персонажу — одной
     * группировкой по всей таблице. [since] = null — за всё время. Нужна
     * HOT-метке: кто пишет персонажу, важнее, чем сколько.
     */
    suspend fun messageCountsByCharacterAndUser(since: UtcTimestamp?): List<CharacterUserActivity> = dbQuery {
        val t = Tables.CharacterActivityLogs
        val cnt = t.id.count()
        t.select(t.characterId, t.userId, cnt)
            .where {
                val byType = t.activityType eq ActivityType.MESSAGE_SENT.code
                if (since == null) byType else byType and (t.timestamp greaterEq since.toString())
            }
            .groupBy(t.characterId, t.userId)
            .map { CharacterUserActivity(it[t.characterId], it[t.userId], it[cnt].toInt()) }
    }
}

data class CharacterUserActivity(val characterId: String, val userId: String, val messages: Int)
