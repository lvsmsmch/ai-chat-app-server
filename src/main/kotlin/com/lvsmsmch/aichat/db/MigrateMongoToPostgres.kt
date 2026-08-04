package com.lvsmsmch.aichat.db

import com.lvsmsmch.aichat._common.database.DeletedIdsStatsDbo
import com.lvsmsmch.aichat._common.database.ReportDbo
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionDbo
import com.lvsmsmch.aichat.cache.database.CategoryRecommendationsCacheDbo
import com.lvsmsmch.aichat.cache.database.CharacterListCopyDbo
import com.lvsmsmch.aichat.cache.database.DefaultRecommendationsCacheDbo
import com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheDbo
import com.lvsmsmch.aichat.cache.database.UserRecommendationsCacheDbo
import com.lvsmsmch.aichat.character.database.CharacterActivityLogDbo
import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.character.database.CharacterLikeDbo
import com.lvsmsmch.aichat.character.database.SearchSuggestionDbo
import com.lvsmsmch.aichat.chat.database.ChatDbo
import com.lvsmsmch.aichat.chat.database.MessageDbo
import com.lvsmsmch.aichat.comment.database.CommentDbo
import com.lvsmsmch.aichat.comment.database.CommentLikeDbo
import com.lvsmsmch.aichat.feedback.database.FeedbackDbo
import com.lvsmsmch.aichat.notification.database.UserNotificationDbo
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.review.database.ReviewLikeDbo
import com.lvsmsmch.aichat.user.database.DeviceLimitCarryoverDbo
import com.lvsmsmch.aichat.user.database.FollowDbo
import com.lvsmsmch.aichat.user.database.UserDbo
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.BatchInsertStatement
import org.jetbrains.exposed.sql.transactions.transaction
import org.litote.kmongo.reactivestreams.KMongo
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.coroutine

/**
 * Одноразовая переливка данных из Mongo в Postgres.
 *
 * Запускается вручную ОДИН раз при переезде, на той же машине, где живут обе
 * базы. Идемпотентна: каждая таблица перед заливкой очищается, поэтому повторный
 * запуск даёт тот же результат, а не дубли.
 *
 * Запуск:
 *   java -cp server-all.jar com.lvsmsmch.aichat.db.MigrateMongoToPostgresKt
 *
 * Ничего не удаляет в Mongo — она остаётся как откат.
 */
private const val MONGO_DB = "ai_chat_app_database"

fun main() = runBlocking {
    val mongoUri = System.getenv("MONGODB_URI") ?: "mongodb://localhost:27017"
    val mongo = KMongo.createClient(mongoUri).coroutine.getDatabase(MONGO_DB)

    Db.connect()
    Db.createSchema(Tables.all)
    println("Схема Postgres готова: ${Tables.all.size} таблиц")

    val report = mutableListOf<Triple<String, Long, Long>>()

    suspend fun <T : Any> move(
        collectionName: String,
        collection: CoroutineCollection<T>,
        table: Table,
        fill: BatchInsertStatement.(T) -> Unit,
    ) {
        val docs = collection.find().toList()
        transaction(Db.database) {
            table.deleteAll()
            // Пачками: на 2 ГБ памяти незачем держать в одном запросе всё
            docs.chunked(500).forEach { chunk ->
                table.batchInsert(chunk, shouldReturnGeneratedValues = false) { doc -> fill(doc) }
            }
        }
        val inPg = transaction(Db.database) { table.selectAll().count() }
        report += Triple(collectionName, docs.size.toLong(), inPg)
        println("%-32s mongo %5d → pg %5d".format(collectionName, docs.size, inPg))
    }

    move("users", mongo.getCollection<UserDbo>("users"), Tables.Users) { from(it) }
    move("characters", mongo.getCollection<CharacterDbo>("characters"), Tables.Characters) { from(it) }
    move("chats", mongo.getCollection<ChatDbo>("chats"), Tables.Chats) { from(it) }
    move("messages", mongo.getCollection<MessageDbo>("messages"), Tables.Messages) { from(it) }
    move("sessions", mongo.getCollection<SessionDbo>("sessions"), Tables.Sessions) { from(it) }
    move("comments", mongo.getCollection<CommentDbo>("comments"), Tables.Comments) { from(it) }
    move(
        "comment_likes",
        mongo.getCollection<CommentLikeDbo>("comment_likes"),
        Tables.CommentLikes,
    ) { from(it) }
    move("reviews", mongo.getCollection<ReviewDbo>("reviews"), Tables.Reviews) { from(it) }
    move(
        "review_likes",
        mongo.getCollection<ReviewLikeDbo>("review_likes"),
        Tables.ReviewLikes,
    ) { from(it) }
    move(
        "character_likes",
        mongo.getCollection<CharacterLikeDbo>("character_likes"),
        Tables.CharacterLikes,
    ) { from(it) }
    move("follows", mongo.getCollection<FollowDbo>("follows"), Tables.Follows) { from(it) }
    move("reports", mongo.getCollection<ReportDbo>("reports"), Tables.Reports) { from(it) }
    move("feedbacks", mongo.getCollection<FeedbackDbo>("feedbacks"), Tables.Feedbacks) { from(it) }
    move(
        "search_suggestions",
        mongo.getCollection<SearchSuggestionDbo>("search_suggestions"),
        Tables.SearchSuggestions,
    ) { from(it) }
    move(
        "user_notifications",
        mongo.getCollection<UserNotificationDbo>("user_notifications"),
        Tables.UserNotifications,
    ) { from(it) }
    move(
        "character_activity_logs",
        mongo.getCollection<CharacterActivityLogDbo>("character_activity_logs"),
        Tables.CharacterActivityLogs,
    ) { from(it) }
    move(
        "user_recommendations_cache",
        mongo.getCollection<UserRecommendationsCacheDbo>("user_recommendations_cache"),
        Tables.UserRecommendationsCache,
    ) { from(it) }
    move(
        "category_cache",
        mongo.getCollection<CategoryRecommendationsCacheDbo>("category_cache"),
        Tables.CategoryRecommendationsCache,
    ) { from(it) }
    move(
        "default_personalized_cache",
        mongo.getCollection<DefaultRecommendationsCacheDbo>("default_personalized_cache"),
        Tables.DefaultRecommendationsCache,
    ) { from(it) }
    move(
        "discover_sections",
        mongo.getCollection<DiscoverSectionsCacheDbo>("discover_sections"),
        Tables.DiscoverSectionsCache,
    ) { from(it) }
    move(
        "character_list_copy_dbo",
        mongo.getCollection<CharacterListCopyDbo>("character_list_copy_dbo"),
        Tables.CharacterListCopies,
    ) { from(it) }
    move(
        "device_limit_carryover",
        mongo.getCollection<DeviceLimitCarryoverDbo>("device_limit_carryover"),
        Tables.DeviceLimitCarryovers,
    ) { from(it) }
    move(
        "entity_id_stats",
        mongo.getCollection<DeletedIdsStatsDbo>("entity_id_stats"),
        Tables.DeletedIdsStats,
    ) { from(it) }

    val mismatched = report.filter { it.second != it.third }
    println()
    println("Итого: перенесено ${report.sumOf { it.third }} записей из ${report.sumOf { it.second }}")
    if (mismatched.isEmpty()) {
        println("Расхождений нет.")
    } else {
        println("РАСХОЖДЕНИЯ:")
        mismatched.forEach { (name, m, p) -> println("  $name: mongo $m, pg $p") }
        throw IllegalStateException("Переливка неполная: ${mismatched.size} таблиц не совпали")
    }
}
