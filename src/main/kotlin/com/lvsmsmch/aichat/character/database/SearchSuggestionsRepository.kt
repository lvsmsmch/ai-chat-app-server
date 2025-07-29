package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.reactivestreams.client.ClientSession
import kotlinx.coroutines.runBlocking
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

class SearchSuggestionsRepository(
    private val collection: CoroutineCollection<SearchSuggestionDbo>
) {

    /**
     * Initialize indexes for the collection
     */

    init {
        initializeIndexes()
    }

    private fun initializeIndexes() {
        runBlocking {
            // Основной индекс для regex поиска по префиксу
            collection.ensureIndex(ascending(SearchSuggestionDbo::term))

            // Для сортировки по популярности
            collection.ensureIndex(descending(SearchSuggestionDbo::searchCount))

            // Compound индекс для оптимальной производительности
            collection.ensureIndex(
                compoundIndex(
                    ascending(SearchSuggestionDbo::term),
                    descending(SearchSuggestionDbo::searchCount)
                )
            )
        }
    }

    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    suspend fun addSuggestion(session: ClientSession, originalText: String, isCharacterName: Boolean = false) {
        val normalizedTerm = originalText.trim().lowercase()
        val existing = collection.findOneById(normalizedTerm)
        if (existing == null) {
            val suggestion = SearchSuggestionDbo(
                term = normalizedTerm,
                displayText = normalizedTerm,
                searchCount = 1,
                isCharacterName = isCharacterName,
                lastSearchedAt = UtcTimestamp.now()
            )
            collection.insertOne(session, suggestion)
        }
    }

    suspend fun updateSuggestion(session: ClientSession, oldText: String, newText: String, isCharacterName: Boolean = false) {
        val oldNormalizedTerm = oldText.trim().lowercase()
        val newNormalizedTerm = newText.trim().lowercase()

        // Если термины одинаковые, ничего не делаем
        if (oldNormalizedTerm == newNormalizedTerm) return

        // Удаляем старое предложение
        collection.deleteOneById(session, oldNormalizedTerm)

        // Добавляем новое предложение
        val existing = collection.findOneById(newNormalizedTerm)
        if (existing == null) {
            val suggestion = SearchSuggestionDbo(
                term = newNormalizedTerm,
                displayText = newText.trim(), // Сохраняем оригинальный регистр для отображения
                searchCount = 1,
                isCharacterName = isCharacterName,
                lastSearchedAt = UtcTimestamp.now()
            )
            collection.insertOne(session, suggestion)
        } else {
            // Если новое предложение уже существует, просто обновляем его
            collection.replaceOneById(
                session,
                newNormalizedTerm,
                existing.copy(
                    displayText = newText.trim(),
                    isCharacterName = isCharacterName,
                    lastSearchedAt = UtcTimestamp.now()
                )
            )
        }
    }
    
    suspend fun getSuggestions(query: String, limit: Int): List<String> {
        val normalizedQuery = query.trim().lowercase()
        
        return collection.find(
            SearchSuggestionDbo::term.regex("^${Regex.escape(normalizedQuery)}.*", "i")
        ).sort(descending(SearchSuggestionDbo::searchCount))
         .limit(limit)
         .toList()
         .map { it.displayText }
    }
    
    suspend fun recordSearch(query: String) {
        val normalizedTerm = query.trim().lowercase()
        collection.updateOneById(
            normalizedTerm,
            combine(
                inc(SearchSuggestionDbo::searchCount, 1),
                setValue(SearchSuggestionDbo::lastSearchedAt, UtcTimestamp.now())
            )
        )
    }
}