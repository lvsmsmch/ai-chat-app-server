package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.cache.database.DEFAULT_DISCOVER_CACHE_ID
import com.lvsmsmch.aichat.cache.database.DiscoverSectionDbo
import com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository
import com.lvsmsmch.aichat.cache.database.UserRecommendationsCacheRepository
import com.lvsmsmch.aichat.character.database.CharacterCategory
import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Генерация секций вкладки For you для КАЖДОГО юзера + дефолтный набор для
 * новорегов ("__default").
 *
 * Главные секции — по 30 персов: trending (популярные сейчас, trendingScore),
 * most_popular (за всё время, totalChats), for_you (персонализация из
 * user_recommendations_cache — истории чатов юзера; без истории — микс
 * trending+popular). Первые 5 позиций главных секций НЕ пересекаются между
 * собой (это самые видные места), хвосты могут; внутри секции дублей нет.
 *
 * Категорийные секции — по 10 лучших (trendingScore, затем totalChats);
 * категории с < 3 персонажами пропускаются. Пересечения с главными секциями
 * здесь не считаются проблемой.
 */
fun configureDiscoverSectionsUpdater(
    databaseScope: CoroutineScope,
    userRepository: UserRepository,
    characterRepository: CharacterRepository,
    userCacheRepository: UserRecommendationsCacheRepository,
    discoverSectionsRepository: DiscoverSectionsCacheRepository,
    updateIntervalMinutes: Long = 60,
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                logger.info("Discover sections: rebuild started")
                val all = characterRepository.getAllPublicCharacters()
                val trendingPool = all.sortedByDescending { it.trendingScore }
                val popularPool = all.sortedByDescending { it.totalChats }
                // Микс для юзеров без истории: чередуем тренды и вечную классику
                val mixedPool = interleave(trendingPool, popularPool)

                val categorySections = buildCategorySections(all)

                // Дефолтный набор (новореги): for_you = микс
                discoverSectionsRepository.upsert(
                    DEFAULT_DISCOVER_CACHE_ID,
                    buildSections(mixedPool, trendingPool, popularPool) + categorySections,
                )

                // Активные за месяц юзеры; остальным эндпоинт отдаёт дефолт
                val byId = all.associateBy { it.id }
                var updated = 0
                val monthAgo = com.lvsmsmch.aichat.utils.UtcTimestamp.now().subtractDays(30)
                userRepository.getActiveUsersSince(monthAgo).chunked(50).forEach { batch ->
                    batch.forEach { user ->
                        val personalIds = userCacheRepository.getCachedRecommendations(user.id)
                        val personalPool = personalIds.mapNotNull { byId[it] }
                            .ifEmpty { mixedPool }
                        discoverSectionsRepository.upsert(
                            user.id,
                            buildSections(personalPool, trendingPool, popularPool) + categorySections,
                        )
                        updated++
                    }
                    delay(50)
                }
                logger.info("Discover sections: rebuilt for $updated users + default")
                delay(TimeUnit.MINUTES.toMillis(updateIntervalMinutes))
            } catch (e: CancellationException) {
                logger.debug("Discover sections updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Discover sections updater error: ${e.message}", e)
                delay(TimeUnit.MINUTES.toMillis(10))
            }
        }
    }

    return parentJob
}

private const val MAIN_SECTION_SIZE = 30
private const val HEAD_SIZE = 5
private const val CATEGORY_SECTION_SIZE = 10
private const val MIN_CATEGORY_CHARACTERS = 3

/** Главные секции с гарантией уникальности первых [HEAD_SIZE] позиций. */
private fun buildSections(
    forYouPool: List<CharacterDbo>,
    trendingPool: List<CharacterDbo>,
    popularPool: List<CharacterDbo>,
): List<DiscoverSectionDbo> {
    val usedHeads = mutableSetOf<String>()

    fun buildOne(key: String, pool: List<CharacterDbo>): DiscoverSectionDbo {
        // Голова: HEAD_SIZE самых видных позиций — без дублей с головами
        // ДРУГИХ секций
        val head = pool.asSequence()
            .filter { it.id !in usedHeads }
            .take(HEAD_SIZE)
            .toList()
        usedHeads += head.map { it.id }
        // Хвост: добор до 30 из пула; межсекционные дубли ок, внутри — нет
        val inSection = head.map { it.id }.toMutableSet()
        val tail = pool.asSequence()
            .filter { inSection.add(it.id) }
            .take(MAIN_SECTION_SIZE - head.size)
            .toList()
        return DiscoverSectionDbo(key, (head + tail).map { it.id })
    }

    // Порядок построения = приоритет на «непересекающиеся головы»
    return listOf(
        buildOne("for_you", forYouPool),
        buildOne("trending", trendingPool),
        buildOne("most_popular", popularPool),
    )
}

private fun buildCategorySections(all: List<CharacterDbo>): List<DiscoverSectionDbo> =
    CharacterCategory.entries.mapNotNull { category ->
        val chars = all.filter { it.category == category.code }
        if (chars.size < MIN_CATEGORY_CHARACTERS) return@mapNotNull null
        DiscoverSectionDbo(
            key = category.code,
            characterIds = chars
                .sortedWith(
                    compareByDescending<CharacterDbo> { it.trendingScore }
                        .thenByDescending { it.totalChats }
                )
                .take(CATEGORY_SECTION_SIZE)
                .map { it.id },
        )
    }

private fun interleave(a: List<CharacterDbo>, b: List<CharacterDbo>): List<CharacterDbo> {
    val seen = mutableSetOf<String>()
    val out = ArrayList<CharacterDbo>(a.size + b.size)
    val maxSize = maxOf(a.size, b.size)
    for (i in 0 until maxSize) {
        a.getOrNull(i)?.let { if (seen.add(it.id)) out.add(it) }
        b.getOrNull(i)?.let { if (seen.add(it.id)) out.add(it) }
    }
    return out
}
