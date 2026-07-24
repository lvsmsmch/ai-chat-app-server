package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.reactivestreams.client.ClientSession
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.insertOne

class SearchSuggestionsRepository(
    private val collection: CoroutineCollection<SearchSuggestionDbo>
) {



    suspend fun ensureIndexes() {
        collection.ensureIndex(ascending(SearchSuggestionDbo::term))

        collection.ensureIndex(descending(SearchSuggestionDbo::searchCount))

        collection.ensureIndex(
            compoundIndex(
                ascending(SearchSuggestionDbo::term),
                descending(SearchSuggestionDbo::searchCount)
            )
        )
    }


    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    suspend fun addDefaultSuggestions(suggestions: List<String>) {
        if (suggestions.isEmpty()) return

        val suggestionsToInsert = mutableListOf<SearchSuggestionDbo>()

        suggestions.forEach { originalText ->
            val normalizedTerm = originalText.trim().lowercase()
            val existing = collection.findOneById(normalizedTerm)
            if (existing == null) {
                val suggestion = SearchSuggestionDbo(
                    term = normalizedTerm,
                    displayText = originalText.trim(),
                    isAllowedToShow = true,
                    searchCount = 1,
                    isCharacterName = false,
                    lastSearchedAt = UtcTimestamp.now().toString()
                )
                suggestionsToInsert.add(suggestion)
            }
        }

        if (suggestionsToInsert.isNotEmpty()) {
            collection.insertMany(suggestionsToInsert)
        }
    }

    suspend fun addCharacterName(session: ClientSession, originalText: String) {
        val normalizedTerm = originalText.trim().lowercase()
        val existing = collection.findOneById(normalizedTerm)
        if (existing == null) {
            val suggestion = SearchSuggestionDbo(
                term = normalizedTerm,
                // Показываем имя в оригинальном регистре, а не lowercase
                displayText = originalText.trim(),
                searchCount = 1,
                isCharacterName = true,
                lastSearchedAt = UtcTimestamp.now().toString()
            )
            collection.insertOne(session, suggestion)
        }
    }

    suspend fun updateCharacterName(session: ClientSession, oldText: String, newText: String) {
        val oldNormalizedTerm = oldText.trim().lowercase()
        val newNormalizedTerm = newText.trim().lowercase()

        if (oldNormalizedTerm == newNormalizedTerm) return

        collection.deleteOneById(session, oldNormalizedTerm)

        val existing = collection.findOneById(newNormalizedTerm)
        if (existing == null) {
            val suggestion = SearchSuggestionDbo(
                term = newNormalizedTerm,
                displayText = newText.trim(),
                searchCount = 1,
                isCharacterName = true,
                lastSearchedAt = UtcTimestamp.now().toString()
            )
            collection.insertOne(session, suggestion)
        } else {
            collection.replaceOneById(
                session,
                newNormalizedTerm,
                existing.copy(
                    displayText = newText.trim(),
                    isCharacterName = true,
                    lastSearchedAt = UtcTimestamp.now().toString()
                )
            )
        }
    }
    
    /**
     * Подсказки: имена персонажей + кураторские дефолты + популярные юзерские
     * запросы (от 5 поисков — защита от мусора и чужих случайных строк).
     * Матчинг: сначала префикс всего термина, потом начало любого слова
     * («shino» находит «Kaguya Shinomiya»).
     */
    suspend fun getSuggestions(query: String, limit: Int): List<String> {
        val normalizedQuery = query.trim().lowercase()
        val visible = org.litote.kmongo.or(
            SearchSuggestionDbo::isCharacterName eq true,
            SearchSuggestionDbo::isAllowedToShow eq true,
            SearchSuggestionDbo::searchCount gte 5,
        )
        val prefix = collection.find(
            and(visible, SearchSuggestionDbo::term.regex("^${Regex.escape(normalizedQuery)}", "i"))
        ).sort(descending(SearchSuggestionDbo::searchCount))
            .limit(limit)
            .toList()
        if (prefix.size >= limit || normalizedQuery.isBlank()) {
            return prefix.take(limit).map { it.displayText }
        }
        // Добираем совпадениями по началу слова внутри термина
        val wordStart = collection.find(
            and(visible, SearchSuggestionDbo::term.regex("\\s${Regex.escape(normalizedQuery)}", "i"))
        ).sort(descending(SearchSuggestionDbo::searchCount))
            .limit(limit)
            .toList()
        val known = prefix.map { it.term }.toSet()
        return (prefix + wordStart.filterNot { it.term in known })
            .take(limit)
            .map { it.displayText }
    }
    
    suspend fun recordSearch(query: String) {
        val normalizedTerm = query.trim().lowercase()
        val existing = collection.findOneById(normalizedTerm)
        if (existing == null) {
            val suggestion = SearchSuggestionDbo(
                term = normalizedTerm,
                displayText = normalizedTerm,
                isAllowedToShow = false,
                searchCount = 1,
                isCharacterName = false,
                lastSearchedAt = UtcTimestamp.now().toString()
            )
            collection.insertOne(suggestion)
        } else {
            collection.updateOneById(
                normalizedTerm,
                combine(
                    inc(SearchSuggestionDbo::searchCount, 1),
                    setValue(SearchSuggestionDbo::lastSearchedAt, UtcTimestamp.now().toString())
                )
            )
        }
    }
}