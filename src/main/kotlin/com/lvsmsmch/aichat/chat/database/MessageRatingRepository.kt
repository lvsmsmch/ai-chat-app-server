package com.lvsmsmch.aichat.chat.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toMessageRatingDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/**
 * Оценка одного ответа ИИ. Это снимок на момент оценки, а не ссылки: строка
 * должна остаться пригодной для анализа даже после того, как чат, сообщение
 * или персонаж удалены.
 */
@Serializable
data class MessageRatingDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val createdAt: String = UtcTimestamp.now().toString(),
    val updatedAt: String = UtcTimestamp.now().toString(),
    /** 1 — палец вверх, -1 — вниз, 0 — оценку сняли. */
    val rating: Int,
    val userId: String,
    val userHasSubscription: Boolean = false,
    val messageId: String,
    val messageClientId: String,
    val chatId: String,
    val chatType: String,
    val characterId: String,
    val characterName: String? = null,
    val characterAuthorId: String? = null,
    val characterCategory: String? = null,
    val isImage: Boolean = false,
    val messageText: String = "",
    val imageUrl: String? = null,
    val variantIndex: Int = 0,
    val variantsCount: Int = 0,
    val model: String? = null,
    val generationInfo: String? = null,
    val nsfw: Boolean = false,
    val language: String? = null,
    val messageCreatedAt: String? = null,
)

class MessageRatingRepository {

    /**
     * Поставить/изменить/снять оценку. Ключ — «сообщение + вариант + юзер»:
     * повторная оценка того же варианта обновляет строку (и снимок вместе с
     * ней — текст мог измениться правкой), новых записей не плодит.
     */
    suspend fun rate(dbo: MessageRatingDbo): Unit = dbQuery {
        val existing = Tables.MessageRatings.selectAll()
            .where {
                (Tables.MessageRatings.messageId eq dbo.messageId) and
                    (Tables.MessageRatings.variantIndex eq dbo.variantIndex) and
                    (Tables.MessageRatings.userId eq dbo.userId)
            }
            .limit(1)
            .firstOrNull()
            ?.toMessageRatingDbo()

        if (existing == null) {
            Tables.MessageRatings.insert { it.from(dbo) }
        } else {
            Tables.MessageRatings.update({ Tables.MessageRatings.id eq existing.id }) {
                // createdAt — момент ПЕРВОЙ оценки, он не переписывается
                it.from(dbo.copy(id = existing.id, createdAt = existing.createdAt))
            }
        }
    }

    /** Текущие оценки юзера по списку сообщений: id сообщения → оценка. */
    suspend fun ratingsFor(userId: String, messageIds: List<String>): Map<String, Int> =
        if (messageIds.isEmpty()) emptyMap() else dbQuery {
            Tables.MessageRatings.selectAll()
                .where {
                    (Tables.MessageRatings.userId eq userId) and
                        (Tables.MessageRatings.messageId inList messageIds)
                }
                .associate { it[Tables.MessageRatings.messageId] to it[Tables.MessageRatings.rating] }
        }

    /** Выгрузка для статистики: свежие оценки, при желании только одного знака. */
    suspend fun recent(limit: Int = 100, rating: Int? = null): List<MessageRatingDbo> = dbQuery {
        Tables.MessageRatings.selectAll()
            .apply { if (rating != null) where { Tables.MessageRatings.rating eq rating } }
            .orderBy(Tables.MessageRatings.updatedAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toMessageRatingDbo() }
    }
}
