package com.lvsmsmch.aichat.auth.database.tokens.session_tokens

import com.lvsmsmch.aichat.auth.database.tokens.TokenDbo
import com.lvsmsmch.aichat.auth.database.tokens.TokenRepository
import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toSessionDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.generateToken
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

@Serializable
data class SessionDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    override val token: String,
    override val createdAt: String = UtcTimestamp.now().toString(),
    override val expiresAt: String = UtcTimestamp.now().addDays(SessionRepository.SESSION_DAYS).toString(),
    val userId: String,
    val ipAddress: String,
) : TokenDbo

class SessionRepository : TokenRepository<SessionDbo> {

    companion object {
        /**
         * Год. Раньше стояло 100 000 дней — то есть сессия была вечной, и в
         * базе копились токены, которые никто уже не использует.
         */
        const val SESSION_DAYS = 365L
        const val RENEW_BEFORE_DAYS = 30L
    }

    override suspend fun get(token: String): SessionDbo? {
        val session = dbQuery {
            Tables.Sessions.selectAll()
                .where { Tables.Sessions.token eq token }
                .limit(1)
                .firstOrNull()
                ?.toSessionDbo()
        } ?: return null
        renewIfExpiringSoon(session)
        return session
    }

    /**
     * Скользящее продление: у активного приложения срок не должен истечь под
     * руками. Пишем в базу только когда до конца осталось меньше
     * [RENEW_BEFORE_DAYS] — иначе это был бы UPDATE на каждый запрос.
     */
    private suspend fun renewIfExpiringSoon(session: SessionDbo) {
        val expires = runCatching { UtcTimestamp.parse(session.expiresAt) }.getOrNull() ?: return
        val threshold = UtcTimestamp.now().addDays(RENEW_BEFORE_DAYS)
        if (expires.toString() > threshold.toString()) return
        val extended = UtcTimestamp.now().addDays(SESSION_DAYS).toString()
        dbQuery {
            Tables.Sessions.update({ Tables.Sessions.token eq session.token }) {
                it[expiresAt] = extended
            }
        }
    }

    /** Чистка: истёкшие сессии копились бы вечно, проверка их и так не пускает. */
    suspend fun deleteExpired(): Int = dbQuery {
        Tables.Sessions.deleteWhere { Tables.Sessions.expiresAt less UtcTimestamp.now().toString() }
    }

    override suspend fun delete(token: String) {
        dbQuery { Tables.Sessions.deleteWhere { Tables.Sessions.token eq token } }
    }

    /** Все сессии юзера — под нож при удалении аккаунта (токены гаснут сразу). */
    suspend fun deleteAllByUserId(userId: String) {
        dbQuery { Tables.Sessions.deleteWhere { Tables.Sessions.userId eq userId } }
    }

    /**
     * Все сессии кроме текущей — при смене пароля: остальные устройства должны
     * разлогиниться, а тот, кто меняет пароль, остаться в приложении.
     */
    suspend fun deleteAllByUserIdExcept(userId: String, keepToken: String) {
        dbQuery {
            Tables.Sessions.deleteWhere {
                (Tables.Sessions.userId eq userId) and (Tables.Sessions.token neq keepToken)
            }
        }
    }

    suspend fun createSession(userId: String, ipAddress: String): SessionDbo {
        val obj = SessionDbo(token = generateToken(), userId = userId, ipAddress = ipAddress)
        dbQuery { Tables.Sessions.insert { it.from(obj) } }
        return obj
    }
}
