package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.encodeFloatMap
import com.lvsmsmch.aichat.db.encodeStringList
import com.lvsmsmch.aichat.db.encodeTranslations
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toCharacterDbo
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Case
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateStatement
import org.jetbrains.exposed.sql.update

class CharacterRepository {

    private val table = Tables.Characters

    suspend fun addCharacter(session: DbSession, character: CharacterDbo) {
        dbQuery { table.insert { it.from(character) } }
    }

    /** Точечное обновление одного персонажа. */
    private suspend fun updateById(characterId: String, body: (UpdateStatement) -> Unit): Int =
        dbQuery { table.update({ table.id eq characterId }) { body(it) } }

    suspend fun getCharacters(
        searchQuery: String = "",
        sortCriteria: Int? = null,
        categories: List<CharacterCategory> = CharacterCategory.entries.toList(),
        page: Int,
        size: Int,
        authorId: String? = null,
        visibilityFilter: Int? = null,
    ): List<CharacterDbo> = dbQuery {
        val order: Pair<Column<*>, SortOrder> = when (sortCriteria) {
            CharacterSortCriteria.NEWEST.code -> table.createdAt to SortOrder.DESC
            CharacterSortCriteria.OLDEST.code -> table.createdAt to SortOrder.ASC
            CharacterSortCriteria.HIGHEST_RATING.code -> table.averageRating to SortOrder.DESC
            CharacterSortCriteria.LOWEST_RATING.code -> table.averageRating to SortOrder.ASC
            CharacterSortCriteria.MOST_POPULAR.code -> table.totalMessages to SortOrder.DESC
            CharacterSortCriteria.LEAST_POPULAR.code -> table.totalMessages to SortOrder.ASC
            CharacterSortCriteria.TRENDING.code -> table.trendingScore to SortOrder.DESC
            CharacterSortCriteria.RECOMMENDED.code -> table.recommendationScore to SortOrder.DESC
            else -> table.createdAt to SortOrder.DESC
        }

        val query = table.selectAll()
            .where {
                var op: Op<Boolean> = (table.visibility eq CharacterVisibility.PUBLIC.code) and
                    (table.category inList categories.map { it.code })
                if (searchQuery.isNotBlank()) op = op and searchOp(searchQuery)
                if (authorId != null) op = op and (table.authorId eq authorId)
                if (visibilityFilter != null) op = op and (table.visibility eq visibilityFilter)
                op
            }

        // В поиске сначала идёт релевантность, и только внутри одного уровня —
        // выбранная сортировка. Иначе популярный персонаж со словом "extremely"
        // в описании оказывался выше персонажа с точным именем "Rem".
        if (searchQuery.isNotBlank()) {
            query.orderBy(searchRank(searchQuery) to SortOrder.ASC, order)
        } else {
            query.orderBy(order)
        }

        query
            .limit(size)
            .offset(((page - 1).toLong() * size))
            .map { it.toCharacterDbo() }
    }

    /**
     * Короткие запросы ищем только в структурированных полях и именах переводов:
     * подстрока из трёх букв в свободном описании даёт сотни ложных совпадений
     * (например, `rem` в `extremely`). С четырёх символов подключаем описание.
     */
    private fun searchOp(searchQuery: String): Op<Boolean> {
        val normalized = escapeLike(searchQuery.trim().lowercase())
        val needle = "%$normalized%"
        val translatedName = "%\"name\":\"$normalized%"
        var op = (table.name.lowerCase() like needle) or
            (table.category.lowerCase() like needle) or
            (table.tags.lowerCase() like needle) or
            (table.translations.lowerCase() like translatedName)
        if (searchQuery.trim().length >= 4) {
            op = op or (table.description.lowerCase() like needle) or
                (table.translations.lowerCase() like needle)
        }
        return op
    }

    /** Точное имя → начало имени → имя содержит запрос → остальные поля. */
    private fun searchRank(searchQuery: String) = with(searchQuery.trim().lowercase()) {
        val escaped = escapeLike(this)
        Case()
            .When(table.name.lowerCase() eq this, intLiteral(0))
            .When(table.name.lowerCase() like "$escaped%", intLiteral(1))
            .When(table.name.lowerCase() like "%$escaped%", intLiteral(2))
            .When(table.translations.lowerCase() like "%\"name\":\"$escaped\"%", intLiteral(3))
            .When(table.translations.lowerCase() like "%\"name\":\"$escaped%", intLiteral(4))
            .When(table.tags.lowerCase() like "%$escaped%", intLiteral(5))
            .When(table.category.lowerCase() like "%$escaped%", intLiteral(6))
            .Else(intLiteral(7))
    }

    /** Спецсимволы LIKE в пользовательском вводе не должны работать как шаблон. */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Батч по id, БЕЗ фильтра по видимости: нужен там, где персонажи уже
     * привязаны к сущности (участники чата) и приватность проверена раньше.
     */
    suspend fun getByIds(characterIds: Collection<String>): List<CharacterDbo> {
        if (characterIds.isEmpty()) return emptyList()
        return dbQuery {
            table.selectAll()
                .where { table.id inList characterIds.toList() }
                .map { it.toCharacterDbo() }
        }
    }

    private suspend fun getCharactersByIds(characterIds: List<String>): List<CharacterDbo> {
        if (characterIds.isEmpty()) return emptyList()
        val found = dbQuery {
            table.selectAll()
                .where {
                    (table.id inList characterIds) and
                        (table.visibility eq CharacterVisibility.PUBLIC.code)
                }
                .map { it.toCharacterDbo() }
        }
        // Порядок запрошенных id сохраняется: на нём держатся ленты рекомендаций
        return characterIds.mapNotNull { id -> found.find { it.id == id } }
    }

    suspend fun getCharactersByUserId(
        userId: String,
        includePrivate: Boolean = false,
    ): List<CharacterDbo> = dbQuery {
        table.selectAll()
            .where {
                val base = table.authorId eq userId
                if (includePrivate) base
                else base and (table.visibility eq CharacterVisibility.PUBLIC.code)
            }
            .map { it.toCharacterDbo() }
    }

    suspend fun getCharactersByUserId(
        session: DbSession,
        userId: String,
        includePrivate: Boolean = false,
    ): List<CharacterDbo> = getCharactersByUserId(userId, includePrivate)

    suspend fun getUserCharactersWithCursor(
        userId: String,
        includePrivate: Boolean = false,
        visibility: Int? = null,
        cursor: String? = null,
        size: Int = 10,
    ): CursorResult<CharacterDbo> = dbQuery {
        val beforeTime = cursor?.let { UtcTimestamp.parse(it) }
        val characters = table.selectAll()
            .where {
                var op: Op<Boolean> = table.authorId eq userId
                op = if (includePrivate) {
                    if (visibility != null) op and (table.visibility eq visibility) else op
                } else {
                    op and (table.visibility eq CharacterVisibility.PUBLIC.code)
                }
                if (beforeTime != null) op = op and (table.createdAt less beforeTime.toString())
                op
            }
            .orderBy(table.createdAt to SortOrder.DESC)
            .limit(size + 1)
            .map { it.toCharacterDbo() }

        val hasMore = characters.size > size
        val items = if (hasMore) characters.dropLast(1) else characters
        CursorResult(
            items = items,
            nextCursor = if (hasMore) items.lastOrNull()?.createdAt else null,
            hasMore = hasMore,
        )
    }

    data class CursorResult<T>(
        val items: List<T>,
        val nextCursor: String?,
        val hasMore: Boolean
    ) {
        companion object {
            fun <T> empty(): CursorResult<T> = CursorResult(
                items = emptyList(),
                nextCursor = null,
                hasMore = false
            )

            fun <T> single(item: T): CursorResult<T> = CursorResult(
                items = listOf(item),
                nextCursor = null,
                hasMore = false
            )
        }
    }

    suspend fun getAllPublicCharactersForCategory(category: CharacterCategory): List<CharacterDbo> =
        dbQuery {
            table.selectAll()
                .where {
                    (table.visibility eq CharacterVisibility.PUBLIC.code) and
                        (table.category eq category.code)
                }
                .map { it.toCharacterDbo() }
        }

    suspend fun getAllPublicCharacters(): List<CharacterDbo> = dbQuery {
        table.selectAll()
            .where { table.visibility eq CharacterVisibility.PUBLIC.code }
            .map { it.toCharacterDbo() }
    }

    /** Записывает перевод персонажа на один язык (остальные не трогает). */
    suspend fun updateTranslation(characterId: String, lang: String, t: CharacterTranslationDbo) {
        val current = getCharacter(characterId) ?: return
        val merged = current.translations + (lang to t)
        updateById(characterId) {
            it[table.translations] = encodeTranslations(merged)
        }
    }

    suspend fun getCharacter(characterId: String): CharacterDbo? = dbQuery {
        table.selectAll()
            .where { table.id eq characterId }
            .limit(1)
            .firstOrNull()
            ?.toCharacterDbo()
    }

    suspend fun getCharacter(session: DbSession, characterId: String): CharacterDbo? =
        getCharacter(characterId)

    suspend fun updateCharacter(
        session: DbSession,
        characterId: String,
        name: String? = null,
        description: String? = null,
        prompt: String? = null,
        initialMessage: String? = null,
        visibility: Int? = null,
        pictureUrl: String? = null,
        pictureUrlThumbnail: String? = null,
        removePicture: Boolean? = null,
        category: CharacterCategory? = null,
        tags: List<CharacterTag>? = null,
        cover: String? = null,
    ) {
        val nothingToDo = name == null && description == null && prompt == null &&
            initialMessage == null && visibility == null && pictureUrl == null &&
            pictureUrlThumbnail == null && category == null && tags == null &&
            cover == null && removePicture != true
        if (nothingToDo) return

        updateById(characterId) { statement ->
            name?.let { statement[table.name] = it }
            description?.let { statement[table.description] = it }
            prompt?.let { statement[table.prompt] = it }
            initialMessage?.let { statement[table.initialMessage] = it }
            visibility?.let { statement[table.visibility] = it }
            pictureUrl?.let { statement[table.picUrl] = it }
            pictureUrlThumbnail?.let { statement[table.picUrlThumbnail] = it }
            if (removePicture == true) {
                statement[table.picUrl] = null
                statement[table.picUrlThumbnail] = null
            }
            cover?.let { statement[table.cover] = it }
            category?.let { statement[table.category] = it.code }
            tags?.let { list ->
                statement[table.tags] = encodeStringList(list.map { tag -> tag.code })
            }
        }
    }

    /**
     * Проставляет обложку персонажам, у которых её нет: одна разовая пробежка
     * на старте. Обложка выводится из id, поэтому повторный запуск ничего не
     * перетасует, а новые персонажи получают её сразу при создании.
     */
    suspend fun backfillCovers(): Int = dbQuery {
        val ids = table.select(table.id)
            .where { table.cover.isNull() }
            .map { it[table.id] }
        ids.forEach { id ->
            table.update({ table.id eq id }) {
                it[table.cover] = com.lvsmsmch.aichat.character.ChatCovers.defaultFor(id)
            }
        }
        ids.size
    }

    suspend fun updateAvgRating(session: DbSession, characterId: String, newRating: Float) {
        updateById(characterId) { it[table.averageRating] = newRating }
    }

    suspend fun updateTopRank(characterId: String, rank: Int?) {
        updateById(characterId) { it[table.topRank] = rank }
    }

    suspend fun updateTrendingScore(characterId: String, trendingScore: Float) {
        updateById(characterId) {
            it[table.trendingScore] = trendingScore
            it[table.trendingScoreUpdatedAt] = UtcTimestamp.now().toString()
        }
    }

    suspend fun updateRecommendationScore(characterId: String, recommendationScore: Float) {
        updateById(characterId) {
            it[table.recommendationScore] = recommendationScore
            it[table.recommendationScoreUpdatedAt] = UtcTimestamp.now().toString()
        }
    }

    suspend fun updateCoOccurrenceScore(characterId: String, scores: Map<String, Float>) {
        updateById(characterId) {
            it[table.coOccurrenceScore] = encodeFloatMap(scores)
            it[table.coOccurrenceScoreUpdatedAt] = UtcTimestamp.now().toString()
        }
    }

    suspend fun incrementLikesCount(characterId: String, increment: Int) {
        updateById(characterId) { it[table.totalLikes] = table.totalLikes plus increment }
    }

    suspend fun updateSimilarCharacters(characterId: String, ids: List<String>) {
        updateById(characterId) {
            it[table.similarCharacterIds] = encodeStringList(ids)
            it[table.similarCharactersUpdatedAt] = UtcTimestamp.now().toString()
        }
    }

    suspend fun incrementReviewsCount(session: DbSession, characterId: String, increment: Int) {
        updateById(characterId) { it[table.totalReviews] = table.totalReviews plus increment }
    }

    suspend fun incrementCommentsCount(session: DbSession, characterId: String, increment: Int) {
        updateById(characterId) { it[table.totalComments] = table.totalComments plus increment }
    }

    suspend fun incrementChatsCount(session: DbSession, characterId: String, increment: Int) {
        updateById(characterId) { it[table.totalChats] = table.totalChats plus increment }
    }

    suspend fun incrementMessagesCount(session: DbSession, characterId: String, increment: Int) {
        updateById(characterId) { it[table.totalMessages] = table.totalMessages plus increment }
    }

    suspend fun deleteCharacter(session: DbSession, characterId: String) {
        dbQuery { table.deleteWhere { table.id eq characterId } }
    }

    suspend fun deleteCharactersByIds(session: DbSession, characterIds: List<String>) {
        if (characterIds.isEmpty()) return
        dbQuery { table.deleteWhere { table.id inList characterIds } }
    }
}
