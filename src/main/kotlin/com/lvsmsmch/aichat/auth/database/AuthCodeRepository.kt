package com.lvsmsmch.aichat.auth.database

import com.lvsmsmch.aichat.auth.Passwords
import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** Зачем выдан код из письма. */
enum class AuthCodePurpose { EMAIL_VERIFY, PASSWORD_RESET }

data class AuthCodeDbo(
    val id: String,
    val userId: String,
    val purpose: String,
    val codeHash: String,
    val email: String,
    val createdAt: String,
    val expiresAt: String,
    val attempts: Int,
    val consumedAt: String?,
)

/** Что вернула проверка кода — экранам нужны разные сообщения. */
sealed interface CodeCheck {
    data class Ok(val code: AuthCodeDbo) : CodeCheck
    /** Кода нет, он истёк или уже использован. */
    data object NotFound : CodeCheck
    data object Wrong : CodeCheck
    /** Слишком много неверных попыток — код сожжён, нужен новый. */
    data object TooManyAttempts : CodeCheck
}

/**
 * Коды подтверждения почты и сброса пароля.
 *
 * Правила против подбора шести цифр: код живёт [TTL_MINUTES] минут, у него не
 * больше [MAX_ATTEMPTS] попыток, он одноразовый, и на каждый новый запрос
 * прошлые коды того же назначения гасятся — иначе у атакующего был бы не один
 * код, а десяток одновременно живых.
 */
class AuthCodeRepository {

    private val table = Tables.AuthCodes

    /**
     * Создаёт код и возвращает его В ОТКРЫТОМ ВИДЕ (для письма). В базе лежит
     * только bcrypt-хеш — прочитать код из таблицы нельзя.
     */
    suspend fun issue(userId: String, purpose: AuthCodePurpose, email: String): String {
        val code = Passwords.generateEmailCode()
        val now = UtcTimestamp.now()
        dbQuery {
            // Прошлые коды того же назначения больше не действуют
            table.deleteWhere {
                (table.userId eq userId) and (table.purpose eq purpose.name)
            }
            table.insert {
                it[table.id] = UUID.randomUUID().toString()
                it[table.userId] = userId
                it[table.purpose] = purpose.name
                it[table.codeHash] = Passwords.hash(code)
                it[table.email] = email
                it[table.createdAt] = now.toString()
                it[table.expiresAt] = now.addMinutes(TTL_MINUTES).toString()
                it[table.attempts] = 0
                it[table.consumedAt] = null
            }
        }
        return code
    }

    /** Когда последний раз отправляли код — для кулдауна на повторную отправку. */
    suspend fun lastIssuedAt(userId: String, purpose: AuthCodePurpose): String? = dbQuery {
        table.selectAll()
            .where { (table.userId eq userId) and (table.purpose eq purpose.name) }
            .orderBy(table.createdAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(table.createdAt)
    }

    /**
     * Проверяет код и, если он верный, тут же помечает использованным —
     * повторно тем же кодом ничего не сделать.
     */
    suspend fun checkAndConsume(
        userId: String,
        purpose: AuthCodePurpose,
        code: String,
    ): CodeCheck = dbQuery {
        val now = UtcTimestamp.now()
        val row = table.selectAll()
            .where {
                (table.userId eq userId) and
                    (table.purpose eq purpose.name) and
                    table.consumedAt.isNull()
            }
            .orderBy(table.createdAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?: return@dbQuery CodeCheck.NotFound

        val dbo = AuthCodeDbo(
            id = row[table.id],
            userId = row[table.userId],
            purpose = row[table.purpose],
            codeHash = row[table.codeHash],
            email = row[table.email],
            createdAt = row[table.createdAt],
            expiresAt = row[table.expiresAt],
            attempts = row[table.attempts],
            consumedAt = row[table.consumedAt],
        )

        if (UtcTimestamp.parse(dbo.expiresAt).isInPast()) {
            table.deleteWhere { table.id eq dbo.id }
            return@dbQuery CodeCheck.NotFound
        }
        if (dbo.attempts >= MAX_ATTEMPTS) {
            table.deleteWhere { table.id eq dbo.id }
            return@dbQuery CodeCheck.TooManyAttempts
        }
        if (!Passwords.matches(code.trim(), dbo.codeHash)) {
            table.update({ table.id eq dbo.id }) {
                it[table.attempts] = table.attempts plus 1
            }
            return@dbQuery CodeCheck.Wrong
        }
        table.update({ table.id eq dbo.id }) { it[table.consumedAt] = now.toString() }
        CodeCheck.Ok(dbo)
    }

    /** Уборка истёкших кодов — чтобы таблица не росла бесконечно. */
    suspend fun deleteExpired(): Int = dbQuery {
        table.deleteWhere { table.expiresAt less UtcTimestamp.now().toString() }
    }

    suspend fun deleteAllForUser(userId: String) {
        dbQuery { table.deleteWhere { table.userId eq userId } }
    }

    companion object {
        const val TTL_MINUTES = 15L
        const val MAX_ATTEMPTS = 5
        /** Не чаще одного письма в минуту на аккаунт. */
        const val RESEND_COOLDOWN_SECONDS = 60L
    }
}
