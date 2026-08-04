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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

@Serializable
data class SessionDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    override val token: String,
    override val createdAt: String = UtcTimestamp.now().toString(),
    override val expiresAt: String = UtcTimestamp.now().addDays(100_000).toString(),
    val userId: String,
    val ipAddress: String,
) : TokenDbo

class SessionRepository : TokenRepository<SessionDbo> {

    override suspend fun get(token: String): SessionDbo? = dbQuery {
        Tables.Sessions.selectAll()
            .where { Tables.Sessions.token eq token }
            .limit(1)
            .firstOrNull()
            ?.toSessionDbo()
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
