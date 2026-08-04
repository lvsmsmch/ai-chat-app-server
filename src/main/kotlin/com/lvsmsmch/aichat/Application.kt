package com.lvsmsmch.aichat

import com.lvsmsmch.aichat.auth.AppleIdentityTokenVerifier
import com.lvsmsmch.aichat.auth.database.AuthCodeRepository
import com.lvsmsmch.aichat.auth.database.AuthLockoutRepository
import com.lvsmsmch.aichat.character.CharacterService
import com.lvsmsmch.aichat.chat.ChatService
import com.lvsmsmch.aichat.comment.CommentService
import com.lvsmsmch.aichat.mail.MailSender
import com.lvsmsmch.aichat.review.ReviewService
import com.lvsmsmch.aichat.user.UserService
import com.lvsmsmch.aichat.user.database.DeviceLimitCarryoverRepository
import com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository
import com.lvsmsmch.aichat.character.database.CharacterLikeRepository
import com.lvsmsmch.aichat.notification.NotificationService
import com.lvsmsmch.aichat.notification.database.UserNotificationRepository
import org.koin.ktor.ext.inject
import com.lvsmsmch.aichat.db.Db
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.UsernameGenerator
import com.lvsmsmch.aichat._common.database.DeletedIdsStatsDbo
import com.lvsmsmch.aichat._common.database.DeletedIdsStatsRepository
import com.lvsmsmch.aichat._common.database.ReportDbo
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionDbo
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.cache.CacheManager
import com.lvsmsmch.aichat.cache.database.*
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.chat.MessageFinisher
import com.lvsmsmch.aichat.chat.database.ChatDbo
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageDbo
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.feedback.database.FeedbackDbo
import com.lvsmsmch.aichat.feedback.database.FeedbackRepository
import com.lvsmsmch.aichat.comment.database.CommentDbo
import com.lvsmsmch.aichat.comment.database.CommentLikeDbo
import com.lvsmsmch.aichat.comment.database.CommentLikeRepository
import com.lvsmsmch.aichat.comment.database.CommentRepository
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.review.database.ReviewLikeDbo
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.database.FollowDbo
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import com.lvsmsmch.aichat.utils.updaters.*
import com.lvsmsmch.aichat.utils.workers.fillDefaultSuggestions
import com.lvsmsmch.aichat.utils.workers.fillInitialData
import io.ktor.client.HttpClient
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.*
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

fun main() {
    logger.info("Application started...")
    embeddedServer(Netty, port = 8080) {
        logger.info("Server started...")
        module()
        logger.info("Module configured...")
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    // gzip: JSON жмётся в 4-6 раз, крупные списки перестают весить сотни КБ
    install(Compression) { gzip() }
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(60)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    install(RateLimit) {
        global {
            rateLimiter(limit = 1000, refillPeriod = 1.minutes)
        }

        register(RateLimitName("ip-based")) {
            // 100/мин не хватало: активная навигация по приложению (профили,
            // лента, лимиты, синки) легко превышала порог и валила экраны в 429
            rateLimiter(limit = 400, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.origin.remoteHost
            }
        }

        register(RateLimitName("auth-strict")) {
            rateLimiter(limit = 5, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.origin.remoteHost
            }
        }

        register(RateLimitName("rewarded")) {
            rateLimiter(limit = 3, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["Authorization"] ?: call.request.origin.remoteHost
            }
        }
    }

    install(org.koin.ktor.plugin.Koin) {
        org.koin.logger.SLF4JLogger()
        modules(com.lvsmsmch.aichat.di.appModule)
    }

    install(CorrelationIdPlugin)
    configureErrorHandling()

    val databaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Postgres: пул, схема (таблицы + индексы создаются, если их ещё нет)
    Db.connect()
    Db.createSchema(Tables.all)
    logger.info("Postgres connected, schema ensured: ${Tables.all.size} tables")

    // Граф зависимостей собирает Koin (см. di/AppModule.kt). Здесь остаются
    // только те объекты, которые нужны фоновым задачам ниже
    val appleVerifier by inject<AppleIdentityTokenVerifier>()
    val authCodeRepository by inject<AuthCodeRepository>()
    val authLockoutRepository by inject<AuthLockoutRepository>()
    val cacheManager by inject<CacheManager>()
    val categoryRecommendationsCacheRepository by inject<CategoryRecommendationsCacheRepository>()
    val characterActivityLogRepository by inject<CharacterActivityLogRepository>()
    val characterLikeRepository by inject<CharacterLikeRepository>()
    val characterRepository by inject<CharacterRepository>()
    val characterService by inject<CharacterService>()
    val chatRepository by inject<ChatRepository>()
    val chatService by inject<ChatService>()
    val commentLikeRepository by inject<CommentLikeRepository>()
    val commentRepository by inject<CommentRepository>()
    val commentService by inject<CommentService>()
    val defaultRecommendationsCacheRepository by inject<DefaultRecommendationsCacheRepository>()
    val deviceLimitCarryoverRepository by inject<DeviceLimitCarryoverRepository>()
    val discoverSectionsRepository by inject<DiscoverSectionsCacheRepository>()
    val feedbackRepository by inject<FeedbackRepository>()
    val followRepository by inject<FollowRepository>()
    val idGenerator by inject<IdGenerator>()
    val mailSender by inject<MailSender>()
    val mapper by inject<Mapper>()
    val messageFinisher by inject<MessageFinisher>()
    val messageRepository by inject<MessageRepository>()
    val notificationService by inject<NotificationService>()
    val reportRepository by inject<ReportRepository>()
    val reviewLikeRepository by inject<ReviewLikeRepository>()
    val reviewRepository by inject<ReviewRepository>()
    val searchSuggestionsRepository by inject<SearchSuggestionsRepository>()
    val sessionRepository by inject<SessionRepository>()
    val userNotificationRepository by inject<UserNotificationRepository>()
    val userRecommendationsCacheRepository by inject<UserRecommendationsCacheRepository>()
    val userRepository by inject<UserRepository>()
    val userService by inject<UserService>()
    val usernameGenerator by inject<UsernameGenerator>()

    val characterTrendingScoreUpdaterJob = configureCharacterTrendingScoreUpdater(
        databaseScope = databaseScope,
        characterRepository = characterRepository,
        characterActivityLogRepository = characterActivityLogRepository,
        updateIntervalMinutes = 60,
        includeIntervalMinutes = 60 * 24 * 7
    )

    val recommendationScoreUpdaterJob = configureCharacterRecommendationScoreUpdater(
        databaseScope = databaseScope,
        characterRepository = characterRepository,
        updateIntervalMinutes = 60
    )

    val coOccurrenceScoreUpdaterJob = configureCharacterCoOccurrenceUpdater(
        databaseScope = databaseScope,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        updateIntervalMinutes = 60
    )

    val similarCharactersUpdaterJob = configureSimilarCharactersUpdater(
        databaseScope = databaseScope,
        characterRepository = characterRepository,
    )

    val discoverSectionsUpdaterJob = configureDiscoverSectionsUpdater(
        databaseScope = databaseScope,
        userRepository = userRepository,
        characterRepository = characterRepository,
        userCacheRepository = userRecommendationsCacheRepository,
        discoverSectionsRepository = discoverSectionsRepository,
    )

    val characterTranslationsUpdaterJob = configureCharacterTranslationsUpdater(
        databaseScope = databaseScope,
        characterRepository = characterRepository,
    )

    val userRecommendationsUpdaterJob = configureUserRecommendationsUpdater(
        databaseScope = databaseScope,
        userRepository = userRepository,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        userCacheRepository = userRecommendationsCacheRepository,
        updateIntervalMinutes = 60
    )

    val categoryCacheUpdaterJob = configureCategoryRecommendationsUpdater(
        databaseScope = databaseScope,
        characterRepository = characterRepository,
        categoryRecommendationsCacheRepository = categoryRecommendationsCacheRepository,
        updateIntervalMinutes = 5
    )

    val topCharactersUpdaterJob = configureTopCharactersUpdater(
        databaseScope = databaseScope,
        characterRepository = characterRepository,
        updateIntervalHours = 24
    )

    val defaultPersonalizedUpdaterJob = configureDefaultRecommendationsUpdater(
        databaseScope = databaseScope,
        characterRepository = characterRepository,
        defaultRecommendationsCacheRepository = defaultRecommendationsCacheRepository,
        updateIntervalMinutes = 60
    )

    val authCodesCleanupJob = configureAuthCodesCleanupUpdater(
        databaseScope = databaseScope,
        authCodeRepository = authCodeRepository,
    )

    val stuckMessagesUpdaterJob = configureStuckMessagesUpdater(
        databaseScope = databaseScope,
        messageRepository = messageRepository
    )

    val hourlyCounterUpdaterJob = configureHourlyCountersUpdater(
        databaseScope = databaseScope,
        userRepository = userRepository
    )

    val dailyCounterUpdaterJob = configureDailyCountersUpdater(
        databaseScope = databaseScope,
        userRepository = userRepository
    )

    val monthlyCounterUpdaterJob = configureMonthlyCountersUpdater(
        databaseScope = databaseScope,
        userRepository = userRepository
    )

    val winbackPushUpdaterJob = configureWinbackPushUpdater(
        databaseScope = databaseScope,
        userRepository = userRepository
    )

    val fillDefaultSuggestionsJob = fillDefaultSuggestions(
        databaseScope = databaseScope,
        searchSuggestionsRepository = searchSuggestionsRepository
    )

    val fillInitialDataJob = fillInitialData(
        databaseScope = databaseScope,
        userRepository = userRepository,
        idGenerator = idGenerator,
        usernameGenerator = usernameGenerator,
        userService = userService
    )

    configureRouting()

    environment.monitor.subscribe(ApplicationStopping) {
        runBlocking {
            logger.info("Application stopping, cancelling repository connections...")
            characterTrendingScoreUpdaterJob.cancelAndJoin()
            recommendationScoreUpdaterJob.cancelAndJoin()
            coOccurrenceScoreUpdaterJob.cancelAndJoin()
            userRecommendationsUpdaterJob.cancelAndJoin()
            categoryCacheUpdaterJob.cancelAndJoin()
            defaultPersonalizedUpdaterJob.cancelAndJoin()
            stuckMessagesUpdaterJob.cancelAndJoin()
            authCodesCleanupJob.cancelAndJoin()
            hourlyCounterUpdaterJob.cancelAndJoin()
            dailyCounterUpdaterJob.cancelAndJoin()
            fillDefaultSuggestionsJob.cancelAndJoin()
            fillInitialDataJob.cancelAndJoin()

            databaseScope.cancel()
            logger.info("All repository connections have been cancelled")
        }
    }
}