package com.lvsmsmch.aichat.utils.workers

import kotlinx.coroutines.*

import com.lvsmsmch.aichat.character.database.SearchSuggestionsRepository
import com.lvsmsmch.aichat.utils.logger

fun fillDefaultSuggestions(
    databaseScope: CoroutineScope,
    searchSuggestionsRepository: SearchSuggestionsRepository
): Job {
    val parentJob = SupervisorJob()
    val scope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    scope.launch {
        try {
            logger.info("Starting to fill default suggestions")

            val initialSuggestions = getInitialSuggestions()

            initialSuggestions.forEach { term ->
                searchSuggestionsRepository.addSuggestion(term)
            }


            logger.info("Filled database with ${initialSuggestions.size} suggestions")


        } catch (e: CancellationException) {
            logger.debug("Filled default suggestions cancelled")
        } catch (e: Exception) {
            logger.error("Error during filling default suggestions: ${e.message}", e)
        }
    }

    return parentJob
}

private fun getInitialSuggestions(): List<String> {
    return listOf(
        // english
        "anime", "cute", "funny", "cool", "hot", "beautiful",
        "warrior", "princess", "hero", "villain", "robot",

        // japanese
        "アニメ", "かわいい", "美少女", "戦士",

        // russian
        "аниме", "милая", "крутой", "принцесса", "девушка"
    )
}