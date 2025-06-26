package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.cache.database.UserRecommendationsCacheRepository
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp

fun configureUserRecommendationsUpdater(
    logger: Logger,
    databaseScope: CoroutineScope,
    userRepository: UserRepository,
    characterRepository: CharacterRepository,
    chatRepository: ChatRepository,
    userCacheRepository: UserRecommendationsCacheRepository,
    updateIntervalMinutes: Long = 60 // каждый час
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                logger.info("Starting user base cache update")

                // Загружаем всех персонажей один раз
                val allCharacters = characterRepository.getAllPublicCharacters()
                val charactersById = allCharacters.associateBy { it.id }
                logger.info("Loaded ${allCharacters.size} characters for base cache update")

                // Получаем пользователей, которым нужно обновить базовый кеш
                val usersToUpdate = getUsersNeedingBaseCacheUpdate(userRepository, userCacheRepository)
                logger.info("Found ${usersToUpdate.size} users needing base cache update")

                // Обрабатываем пакетами
                usersToUpdate.chunked(50).forEach { batch ->
                    batch.forEach { user ->
                        val recommendedIds = generateBaseRecommendationsForUser(
                            userId = user.id,
                            allCharacters = allCharacters,
                            charactersById = charactersById,
                            chatRepository = chatRepository
                        )

                        userCacheRepository.upsertUserCache(user.id, recommendedIds)

                    }
                    delay(100) // Пауза между пакетами
                }

                // Очистка старых кешей
                val deletedCount = userCacheRepository.deleteInactiveUserCaches()
                if (deletedCount > 0) {
                    logger.info("Cleaned up $deletedCount inactive user base caches")
                }

                logger.info("User base cache update completed")
                delay(TimeUnit.MINUTES.toMillis(updateIntervalMinutes))

            } catch (e: CancellationException) {
                logger.debug("User base cache updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during user base cache update: ${e.message}", e)
                delay(TimeUnit.HOURS.toMillis(1))
            }
        }
    }

    return parentJob
}

private suspend fun getUsersNeedingBaseCacheUpdate(
    userRepository: UserRepository,
    userBaseCacheRepository: UserRecommendationsCacheRepository
): List<UserDbo> {
    val now = UtcTimestamp.now()
    val monthAgo = now.subtractDays(30)

    // Получаем активных пользователей
    val activeUsers = userRepository.getActiveUsersSince(monthAgo)

    return activeUsers.filter { user ->
        val cache = userBaseCacheRepository.getUserCache(user.id)
        if (cache == null) {
            true // Нет кеша - нужно создать
        } else {
            val ttl = determineTTL(user.lastActiveAt, now)
            val expirationTime = cache.updatedAt.addHours(ttl)
            expirationTime.isBefore(now) // Кеш устарел
        }
    }
}

private suspend fun generateBaseRecommendationsForUser(
    userId: String,
    allCharacters: List<CharacterDbo>,
    charactersById: Map<String, CharacterDbo>,
    chatRepository: ChatRepository
): List<String> {
    val userChats = chatRepository.getChatsByUserId(userId).filter { !it.isDeleted }
    val userCharacterIds = userChats.flatMap { it.characterIds }.toSet()

    val availableCharacters = allCharacters.filter { !userCharacterIds.contains(it.id) }

    return if (userCharacterIds.isEmpty()) {
        // Новый пользователь
        availableCharacters
            .sortedByDescending { it.recommendationScore }
            .take(1000)
            .map { it.id }
    } else {
        // Персонализированные рекомендации
        val userCharacters = userCharacterIds.mapNotNull { charactersById[it] }

        val scoredCharacters = availableCharacters.map { character ->
            val score = calculatePersonalizedScore(character, userCharacters)
            character to score
        }.sortedByDescending { it.second }

        scoredCharacters
            .take(1000)
            .map { it.first.id }
    }
}


private fun calculatePersonalizedScore(
    character: CharacterDbo,
    userCharacters: List<CharacterDbo>
): Float {
    var score = character.recommendationScore * 0.3f

    userCharacters.forEach { userChar ->
        if (userChar.category == character.category) score += 0.2f
        val commonTags = userChar.tags.intersect(character.tags.toSet()).size
        score += commonTags * 0.1f
    }

    val userCharacterIds = userCharacters.map { it.id }.toSet()
    character.coOccurrenceScore.forEach { (charId, coScore) ->
        if (charId in userCharacterIds) {
            score += coScore * 0.4f
        }
    }

    return score / maxOf(userCharacters.size, 1)
}

private fun determineTTL(lastActiveAt: UtcTimestamp, now: UtcTimestamp): Long {
    val hoursInactive = java.time.Duration.between(lastActiveAt.instant, now.instant).toHours()

    return when {
        hoursInactive <= 12 -> 3
        hoursInactive <= 24 -> 6
        hoursInactive <= 168 -> 24
        hoursInactive <= 720 -> 168
        else -> 0
    }
}