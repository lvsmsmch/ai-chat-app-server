package com.lvsmsmch.aichat.jobs

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.UsernameGenerator
import com.lvsmsmch.aichat.auth.database.AuthCodeRepository
import com.lvsmsmch.aichat.cache.database.CategoryRecommendationsCacheRepository
import com.lvsmsmch.aichat.cache.database.DefaultRecommendationsCacheRepository
import com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository
import com.lvsmsmch.aichat.cache.database.UserRecommendationsCacheRepository
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.character.database.SearchSuggestionsRepository
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.user.UserService
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.logger
import com.lvsmsmch.aichat.utils.updaters.*
import com.lvsmsmch.aichat.utils.workers.fillDefaultSuggestions
import com.lvsmsmch.aichat.utils.workers.fillInitialData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin

/**
 * Все фоновые задачи сервера в одном месте: пересчёт скорингов, прогрев кэшей,
 * счётчики лимитов, чистки и разовые сидеры.
 *
 * Раньше это были 18 блоков прямо в `Application.kt` — каждой задаче туда
 * тянулся свой репозиторий, а на остановке гасились не все: шесть задач в
 * список `cancelAndJoin` просто не попали и обрывались вместе со скоупом,
 * не дожидаясь конца текущей итерации. Здесь [stop] гасит ровно то, что
 * [start] запустил, — забыть задачу больше негде.
 */
class BackgroundJobs(
    private val userRepository: UserRepository,
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val characterActivityLogRepository: CharacterActivityLogRepository,
    private val userRecommendationsCacheRepository: UserRecommendationsCacheRepository,
    private val categoryRecommendationsCacheRepository: CategoryRecommendationsCacheRepository,
    private val defaultRecommendationsCacheRepository: DefaultRecommendationsCacheRepository,
    private val discoverSectionsRepository: DiscoverSectionsCacheRepository,
    private val searchSuggestionsRepository: SearchSuggestionsRepository,
    private val authCodeRepository: AuthCodeRepository,
    private val idGenerator: IdGenerator,
    private val usernameGenerator: UsernameGenerator,
    private val userService: UserService,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var jobs: List<Job> = emptyList()

    fun start() {
        if (jobs.isNotEmpty()) return

        jobs = listOf(
            configureCharacterTrendingScoreUpdater(
                databaseScope = scope,
                characterRepository = characterRepository,
                characterActivityLogRepository = characterActivityLogRepository,
                updateIntervalMinutes = 60,
                includeIntervalMinutes = 60 * 24 * 7,
            ),
            configureCharacterRecommendationScoreUpdater(
                databaseScope = scope,
                characterRepository = characterRepository,
                updateIntervalMinutes = 60,
            ),
            configureCharacterCoOccurrenceUpdater(
                databaseScope = scope,
                characterRepository = characterRepository,
                chatRepository = chatRepository,
                updateIntervalMinutes = 60,
            ),
            configureSimilarCharactersUpdater(
                databaseScope = scope,
                characterRepository = characterRepository,
            ),
            configureDiscoverSectionsUpdater(
                databaseScope = scope,
                userRepository = userRepository,
                characterRepository = characterRepository,
                userCacheRepository = userRecommendationsCacheRepository,
                discoverSectionsRepository = discoverSectionsRepository,
            ),
            configureCharacterTranslationsUpdater(
                databaseScope = scope,
                characterRepository = characterRepository,
            ),
            configureUserRecommendationsUpdater(
                databaseScope = scope,
                userRepository = userRepository,
                characterRepository = characterRepository,
                chatRepository = chatRepository,
                userCacheRepository = userRecommendationsCacheRepository,
                updateIntervalMinutes = 60,
            ),
            configureCategoryRecommendationsUpdater(
                databaseScope = scope,
                characterRepository = characterRepository,
                categoryRecommendationsCacheRepository = categoryRecommendationsCacheRepository,
                updateIntervalMinutes = 5,
            ),
            configureTopCharactersUpdater(
                databaseScope = scope,
                characterRepository = characterRepository,
                updateIntervalHours = 24,
            ),
            configureDefaultRecommendationsUpdater(
                databaseScope = scope,
                characterRepository = characterRepository,
                defaultRecommendationsCacheRepository = defaultRecommendationsCacheRepository,
                updateIntervalMinutes = 60,
            ),
            configureAuthCodesCleanupUpdater(
                databaseScope = scope,
                authCodeRepository = authCodeRepository,
            ),
            configureStuckMessagesUpdater(
                databaseScope = scope,
                messageRepository = messageRepository,
            ),
            configureHourlyCountersUpdater(
                databaseScope = scope,
                userRepository = userRepository,
            ),
            configureDailyCountersUpdater(
                databaseScope = scope,
                userRepository = userRepository,
            ),
            configureMonthlyCountersUpdater(
                databaseScope = scope,
                userRepository = userRepository,
            ),
            configureWinbackPushUpdater(
                databaseScope = scope,
                userRepository = userRepository,
            ),
            fillDefaultSuggestions(
                databaseScope = scope,
                searchSuggestionsRepository = searchSuggestionsRepository,
            ),
            fillInitialData(
                databaseScope = scope,
                userRepository = userRepository,
                idGenerator = idGenerator,
                usernameGenerator = usernameGenerator,
                userService = userService,
            ),
        )

        logger.info("Background jobs started: ${jobs.size}")
    }

    suspend fun stop() {
        jobs.forEach { it.cancelAndJoin() }
        jobs = emptyList()
        scope.cancel()
        logger.info("Background jobs stopped")
    }
}
