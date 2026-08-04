package com.lvsmsmch.aichat.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Подключение к Postgres. Один пул на процесс, все запросы через [dbQuery].
 *
 * Размер пула держим маленьким: дроплет 1 vCPU / 2 ГБ, и толку от десятков
 * соединений там нет — они только съедают память под каждый backend-процесс.
 */
object Db {

    /**
     * Сериализация полей-коллекций. Списки и мапы документов лежат в TEXT
     * как JSON: их всего несколько (теги, участники чата, переводы,
     * co-occurrence), запросов по содержимому — один, и при 8 тысячах записей
     * jsonb + GIN только усложнили бы схему. Вырастет объём — колонку
     * переведём в jsonb одним ALTER, формат тот же.
     */
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    lateinit var database: Database
        private set

    fun connect(): Database {
        val url = System.getenv("POSTGRES_URL")
            ?: "jdbc:postgresql://127.0.0.1:5432/ai_chat_app"
        val user = System.getenv("POSTGRES_USER") ?: "chat"
        val password = System.getenv("POSTGRES_PASSWORD")
            ?: throw IllegalStateException("Missing POSTGRES_PASSWORD")

        val config = HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 8
            minimumIdle = 2
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            poolName = "chat-pg"
        }
        database = Database.connect(HikariDataSource(config))
        return database
    }

    /** Создание отсутствующих таблиц и индексов при старте. */
    fun createSchema(tables: List<Table>) {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(*tables.toTypedArray())
            extraDdl.forEach { exec(it) }
        }
    }

    /**
     * То, что схемой Exposed не описывается.
     *
     * Уникальность почты — функциональный частичный индекс: сравнение идёт по
     * lower(email), иначе «A@x.com» и «a@x.com» стали бы двумя аккаунтами, а
     * условие NOT NULL оставляет право быть без почты любому числу гостей.
     */
    private val extraDdl = listOf(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_unique
        ON users (lower(email)) WHERE email IS NOT NULL
        """.trimIndent(),
    )

    /**
     * Запрос вне главного потока. Пул JDBC блокирующий, поэтому IO —
     * единственный правильный диспетчер для него.
     *
     * Если транзакция уже открыта (составная операция через TransactionHelper),
     * новую НЕ начинаем — иначе вложенные вызовы репозиториев коммитились бы
     * по отдельности, и откат внешней операции оставлял бы половину записей.
     */
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        if (TransactionManager.currentOrNull() != null) block()
        else newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
