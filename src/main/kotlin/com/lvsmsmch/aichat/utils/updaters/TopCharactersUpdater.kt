package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Топ-3 персонажей в каждой категории за всё время. Пересчёт раз в сутки.
 * Скор смешивает охват и вовлечённость: чаты — уникальные разговоры (вес выше),
 * сообщения — глубина общения.
 */
fun configureTopCharactersUpdater(
    databaseScope: CoroutineScope,
    characterRepository: CharacterRepository,
    updateIntervalHours: Long = 24,
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                logger.info("Starting top characters calculation")
                val all = characterRepository.getAllPublicCharacters()

                val newRanks = mutableMapOf<String, Int>() // characterId -> rank
                all.groupBy { it.category }.forEach { (_, chars) ->
                    chars.sortedByDescending { it.totalChats * 10.0 + it.totalMessages }
                        .take(3)
                        .forEachIndexed { i, c -> newRanks[c.id] = i + 1 }
                }

                var changed = 0
                all.forEach { c ->
                    val rank = newRanks[c.id]
                    if (c.topRank != rank) {
                        characterRepository.updateTopRank(c.id, rank)
                        changed++
                    }
                }
                logger.info("Top characters updated: ${newRanks.size} ranked, $changed changed")

                delay(TimeUnit.HOURS.toMillis(updateIntervalHours))
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                logger.error("Error during top characters update: ${e.message}", e)
                delay(TimeUnit.HOURS.toMillis(1))
            }
        }
    }

    return parentJob
}
