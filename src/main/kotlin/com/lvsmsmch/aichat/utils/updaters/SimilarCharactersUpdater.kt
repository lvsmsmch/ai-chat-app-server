package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln

/**
 * Контентные «похожие персонажи» для ВСЕХ персонажей (5–25 у каждого).
 * Критерии: одна франшиза/вселенная (парсится из описаний вида «X from Y.»),
 * пересечение тегов (черты характера), одна категория. Аниме-персонажам в
 * похожие идут ТОЛЬКО аниме-персонажи; остальным можно смешивать категории.
 * Количество растёт с популярностью (totalChats) и с «похожестью» самого
 * перса: у непопулярного, но типового персонажа список тоже большой.
 * Поведенческий coOccurrenceScore остаётся отдельным сигналом (фолбэк).
 */
fun configureSimilarCharactersUpdater(
    databaseScope: CoroutineScope,
    characterRepository: CharacterRepository,
    updateIntervalMinutes: Long = 24 * 60,
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                logger.info("Starting similar characters calculation")
                val all = characterRepository.getAllPublicCharacters()
                val franchiseById = all.associate { it.id to franchiseOf(it) }

                all.forEach { c ->
                    val myFranchise = franchiseById[c.id]
                    val myTags = c.tags.map { it.lowercase() }.toSet()
                    val scored = all.asSequence()
                        .filter { it.id != c.id }
                        // Аниме-персонажи похожи только на аниме-персонажей
                        .filter { c.category != ANIME || it.category == ANIME }
                        .map { other ->
                            var score = 0.0
                            val otherFranchise = franchiseById[other.id]
                            if (myFranchise != null && myFranchise == otherFranchise) score += 5.0
                            score += other.tags.count { it.lowercase() in myTags } * 1.5
                            if (other.category == c.category) score += 1.0
                            // Близость рейтинга — слабый добавочный сигнал
                            score += 0.5 * (1f - abs(c.averageRating - other.averageRating) / 5f)
                            other to score
                        }
                        // Порог: хоть какое-то реальное сходство (франшиза или теги),
                        // а не только совпавшая категория
                        .filter { it.second >= 2.5 }
                        .sortedByDescending { it.second }
                        .toList()

                    // Целевой размер: 5 базовых + популярность (лог от чатов) +
                    // половина «сильных» совпадений (франшиза) — макс 25
                    val popularityBonus = (ln(1.0 + c.totalChats) * 1.6).toInt()
                    val strongCount = scored.count { it.second >= 5.0 }
                    val target = (5 + popularityBonus + strongCount / 2).coerceIn(5, 25)

                    val picked = scored.take(target).map { it.first.id }.toMutableList()
                    if (picked.size < 5) {
                        // Добор до минимума: топовые по рекомендательному скору из
                        // допустимого пула (для аниме — только аниме)
                        all.asSequence()
                            .filter { it.id != c.id && it.id !in picked }
                            .filter { c.category != ANIME || it.category == ANIME }
                            .sortedByDescending { it.recommendationScore }
                            .take(5 - picked.size)
                            .forEach { picked.add(it.id) }
                    }
                    characterRepository.updateSimilarCharacters(c.id, picked)
                }

                logger.info("Similar characters calculated for ${all.size} characters")
                delay(TimeUnit.MINUTES.toMillis(updateIntervalMinutes))
            } catch (e: CancellationException) {
                logger.debug("Similar characters updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during similar characters calculation: ${e.message}", e)
                cancel()
            }
        }
    }

    return parentJob
}

private const val ANIME = "anime_manga"

/**
 * Франшиза/вселенная из описания: сиды следуют шаблону «Name from Franchise.» —
 * берём кусок после « from » до конца предложения.
 */
private fun franchiseOf(c: CharacterDbo): String? =
    Regex("\\bfrom ([^.!\\n]{2,60})[.!\\n]")
        .find(c.description)
        ?.groupValues?.get(1)
        ?.trim()?.lowercase()
        ?.takeIf { it.isNotBlank() }
