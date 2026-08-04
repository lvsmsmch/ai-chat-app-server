package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toSearchSuggestionDbo
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class SearchSuggestionsRepository {

    private suspend fun exists(term: String): Boolean = dbQuery {
        Tables.SearchSuggestions.selectAll()
            .where { Tables.SearchSuggestions.term eq term }
            .limit(1)
            .any()
    }

    suspend fun addDefaultSuggestions(suggestions: List<String>) {
        if (suggestions.isEmpty()) return
        val toInsert = suggestions.mapNotNull { originalText ->
            val normalizedTerm = originalText.trim().lowercase()
            if (exists(normalizedTerm)) null
            else SearchSuggestionDbo(
                term = normalizedTerm,
                displayText = originalText.trim(),
                isAllowedToShow = true,
                searchCount = 1,
                isCharacterName = false,
                lastSearchedAt = UtcTimestamp.now().toString(),
            )
        }
        if (toInsert.isEmpty()) return
        dbQuery {
            Tables.SearchSuggestions.batchInsert(toInsert, shouldReturnGeneratedValues = false) {
                from(it)
            }
        }
    }

    suspend fun addCharacterName(session: DbSession, originalText: String) {
        val normalizedTerm = originalText.trim().lowercase()
        if (exists(normalizedTerm)) return
        val suggestion = SearchSuggestionDbo(
            term = normalizedTerm,
            // Показываем имя в оригинальном регистре, а не lowercase
            displayText = originalText.trim(),
            searchCount = 1,
            isCharacterName = true,
            lastSearchedAt = UtcTimestamp.now().toString(),
        )
        dbQuery { Tables.SearchSuggestions.insert { it.from(suggestion) } }
    }

    suspend fun updateCharacterName(session: DbSession, oldText: String, newText: String) {
        val oldNormalizedTerm = oldText.trim().lowercase()
        val newNormalizedTerm = newText.trim().lowercase()
        if (oldNormalizedTerm == newNormalizedTerm) return

        dbQuery {
            Tables.SearchSuggestions.deleteWhere {
                Tables.SearchSuggestions.term eq oldNormalizedTerm
            }
            val existing = Tables.SearchSuggestions.selectAll()
                .where { Tables.SearchSuggestions.term eq newNormalizedTerm }
                .limit(1)
                .firstOrNull()
                ?.toSearchSuggestionDbo()

            if (existing == null) {
                Tables.SearchSuggestions.insert {
                    it.from(
                        SearchSuggestionDbo(
                            term = newNormalizedTerm,
                            displayText = newText.trim(),
                            searchCount = 1,
                            isCharacterName = true,
                            lastSearchedAt = UtcTimestamp.now().toString(),
                        )
                    )
                }
            } else {
                val updated = existing.copy(
                    displayText = newText.trim(),
                    isCharacterName = true,
                    lastSearchedAt = UtcTimestamp.now().toString(),
                )
                Tables.SearchSuggestions.update({
                    Tables.SearchSuggestions.term eq newNormalizedTerm
                }) { it.from(updated) }
            }
        }
    }

    /**
     * Подсказки: имена персонажей + кураторские дефолты + популярные юзерские
     * запросы (от 5 поисков — защита от мусора и чужих случайных строк).
     * Матчинг: сначала префикс всего термина, потом начало любого слова
     * («shino» находит «Kaguya Shinomiya»).
     */
    suspend fun getSuggestions(query: String, limit: Int): List<String> = dbQuery {
        val normalizedQuery = query.trim().lowercase()
        val visible = (Tables.SearchSuggestions.isCharacterName eq true) or
            (Tables.SearchSuggestions.isAllowedToShow eq true) or
            (Tables.SearchSuggestions.searchCount greaterEq 5L)

        // LIKE вместо регекса Mongo: term лежит уже в lowercase, поэтому
        // сравнение и так регистронезависимое
        val prefix = Tables.SearchSuggestions.selectAll()
            .where { visible and (Tables.SearchSuggestions.term like "${escapeLike(normalizedQuery)}%") }
            .orderBy(Tables.SearchSuggestions.searchCount to SortOrder.DESC)
            .limit(limit)
            .map { it.toSearchSuggestionDbo() }

        if (prefix.size >= limit || normalizedQuery.isBlank()) {
            prefix.take(limit).map { it.displayText }
        } else {
            // Добираем совпадениями по началу слова внутри термина
            val wordStart = Tables.SearchSuggestions.selectAll()
                .where {
                    visible and (Tables.SearchSuggestions.term like "% ${escapeLike(normalizedQuery)}%")
                }
                .orderBy(Tables.SearchSuggestions.searchCount to SortOrder.DESC)
                .limit(limit)
                .map { it.toSearchSuggestionDbo() }
            val known = prefix.map { it.term }.toSet()
            (prefix + wordStart.filterNot { it.term in known })
                .take(limit)
                .map { it.displayText }
        }
    }

    /** Спецсимволы LIKE в пользовательском вводе не должны работать как шаблон. */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    suspend fun recordSearch(query: String) {
        val normalizedTerm = query.trim().lowercase()
        dbQuery {
            val existing = Tables.SearchSuggestions.selectAll()
                .where { Tables.SearchSuggestions.term eq normalizedTerm }
                .limit(1)
                .any()
            if (!existing) {
                Tables.SearchSuggestions.insert {
                    it.from(
                        SearchSuggestionDbo(
                            term = normalizedTerm,
                            displayText = normalizedTerm,
                            isAllowedToShow = false,
                            searchCount = 1,
                            isCharacterName = false,
                            lastSearchedAt = UtcTimestamp.now().toString(),
                        )
                    )
                }
            } else {
                Tables.SearchSuggestions.update({
                    Tables.SearchSuggestions.term eq normalizedTerm
                }) {
                    it[Tables.SearchSuggestions.searchCount] =
                        Tables.SearchSuggestions.searchCount plus 1L
                    it[Tables.SearchSuggestions.lastSearchedAt] = UtcTimestamp.now().toString()
                }
            }
        }
    }
}
