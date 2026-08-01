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
import kotlin.random.Random

/**
 * Кэш Discover на юзера + дефолтный набор новорегов ("__default").
 *
 * Что лежит в кэше:
 * 1) ПОЛНЫЕ списки вкладок — [MAIN_LIST_SIZE] персонажей в каждом:
 *    most_popular (всего времени, totalChats), trending (сейчас,
 *    trendingScore), for_you (персонализация по истории чатов; без истории —
 *    микс trending+popular). Первые [HEAD_SIZE] позиций этих трёх списков НЕ
 *    пересекаются между собой; дальше дубли допустимы.
 * 2) Секции вкладки All — ключи с префиксом [ALL_PREFIX], по
 *    [ALL_SECTION_SIZE] персонажа: три главные + категории. Внутри экрана All
 *    ВСЕ персонажи уникальны (сначала most_popular, потом trending, for_you,
 *    затем категории). Most popular тут не статичен: 4 случайных из топ-20.
 */
fun configureDiscoverSectionsUpdater(
    databaseScope: CoroutineScope,
    userRepository: UserRepository,
    characterRepository: CharacterRepository,
    userCacheRepository: UserRecommendationsCacheRepository,
    discoverSectionsRepository: DiscoverSectionsCacheRepository,
    updateIntervalMinutes: Long = 20,
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
                val mixedPool = interleave(trendingPool, popularPool)
                val byCategory = all.groupBy { it.category }

                // personalized = есть настоящие рекомендации; иначе секцию For you
                // не отдаём вовсе — она появится, когда наберётся история чатов
                fun buildFor(
                    personalPool: List<CharacterDbo>,
                    personalized: Boolean,
                ): List<DiscoverSectionDbo> =
                    (buildMainLists(personalPool, trendingPool, popularPool) +
                        buildAllScreen(personalPool, trendingPool, popularPool, byCategory))
                        .filterNot { !personalized && it.key.removePrefix(ALL_PREFIX) == KEY_FOR_YOU }

                discoverSectionsRepository.upsert(
                    DEFAULT_DISCOVER_CACHE_ID,
                    buildFor(mixedPool, personalized = false),
                )

                val byId = all.associateBy { it.id }
                var updated = 0
                val monthAgo = com.lvsmsmch.aichat.utils.UtcTimestamp.now().subtractDays(30)
                userRepository.getActiveUsersSince(monthAgo).chunked(50).forEach { batch ->
                    batch.forEach { user ->
                        val recommended = userCacheRepository.getCachedRecommendations(user.id)
                            .mapNotNull { byId[it] }
                        val personalPool = recommended.ifEmpty { mixedPool }
                        discoverSectionsRepository.upsert(
                            user.id,
                            buildFor(personalPool, personalized = recommended.isNotEmpty()),
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

const val ALL_PREFIX = "all:"
const val KEY_FOR_YOU = "for_you"
const val KEY_TRENDING = "trending"
const val KEY_MOST_POPULAR = "most_popular"

private const val MAIN_LIST_SIZE = 200
private const val HEAD_SIZE = 10
private const val ALL_SECTION_SIZE = 4
private const val POPULAR_RANDOM_POOL = 20
private const val MIN_CATEGORY_CHARACTERS = 3

/** Полные списки вкладок: первые HEAD_SIZE не пересекаются между тремя. */
private fun buildMainLists(
    forYouPool: List<CharacterDbo>,
    trendingPool: List<CharacterDbo>,
    popularPool: List<CharacterDbo>,
): List<DiscoverSectionDbo> {
    val usedHeads = mutableSetOf<String>()

    fun buildOne(key: String, pool: List<CharacterDbo>): DiscoverSectionDbo {
        val head = pool.asSequence().filter { it.id !in usedHeads }.take(HEAD_SIZE).toList()
        usedHeads += head.map { it.id }
        val inSection = head.map { it.id }.toMutableSet()
        val tail = pool.asSequence()
            .filter { inSection.add(it.id) }
            .take(MAIN_LIST_SIZE - head.size)
            .toList()
        return DiscoverSectionDbo(key, (head + tail).map { it.id })
    }

    // Порядок = приоритет на непересекающиеся головы
    return listOf(
        buildOne(KEY_MOST_POPULAR, popularPool),
        buildOne(KEY_TRENDING, trendingPool),
        buildOne(KEY_FOR_YOU, forYouPool),
    )
}

/** Секции экрана All: по 4 персонажа, все уникальны в пределах экрана. */
private fun buildAllScreen(
    forYouPool: List<CharacterDbo>,
    trendingPool: List<CharacterDbo>,
    popularPool: List<CharacterDbo>,
    byCategory: Map<String, List<CharacterDbo>>,
): List<DiscoverSectionDbo> {
    val used = mutableSetOf<String>()

    fun pick(pool: List<CharacterDbo>): List<String> =
        pool.asSequence()
            .filter { it.id !in used }
            .take(ALL_SECTION_SIZE)
            .map { it.id }
            .toList()
            .also { used += it }

    val sections = mutableListOf<DiscoverSectionDbo>()
    // Most popular не статичен: случайные 4 из топ-20 по чатам
    sections += DiscoverSectionDbo(
        ALL_PREFIX + KEY_MOST_POPULAR,
        pick(popularPool.take(POPULAR_RANDOM_POOL).shuffled(Random.Default)),
    )
    sections += DiscoverSectionDbo(ALL_PREFIX + KEY_TRENDING, pick(trendingPool))
    sections += DiscoverSectionDbo(ALL_PREFIX + KEY_FOR_YOU, pick(forYouPool))

    // Категории — тоже по 4 и без повторов с уже показанным на экране
    CharacterCategory.entries.forEach { category ->
        val pool = byCategory[category.code].orEmpty()
        if (pool.size < MIN_CATEGORY_CHARACTERS) return@forEach
        val ids = pick(
            pool.sortedWith(
                compareByDescending<CharacterDbo> { it.trendingScore }
                    .thenByDescending { it.totalChats }
            )
        )
        if (ids.isNotEmpty()) sections += DiscoverSectionDbo(ALL_PREFIX + category.code, ids)
    }
    return sections
}

private fun interleave(a: List<CharacterDbo>, b: List<CharacterDbo>): List<CharacterDbo> {
    val seen = mutableSetOf<String>()
    val out = ArrayList<CharacterDbo>(a.size + b.size)
    for (i in 0 until maxOf(a.size, b.size)) {
        a.getOrNull(i)?.let { if (seen.add(it.id)) out.add(it) }
        b.getOrNull(i)?.let { if (seen.add(it.id)) out.add(it) }
    }
    return out
}
