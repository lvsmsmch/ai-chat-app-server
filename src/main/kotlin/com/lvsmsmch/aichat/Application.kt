package com.lvsmsmch.aichat

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

    install(CorrelationIdPlugin)
    configureErrorHandling()

    val databaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Postgres: пул, схема (таблицы + индексы создаются, если их ещё нет)
    Db.connect()
    Db.createSchema(Tables.all)
    logger.info("Postgres connected, schema ensured: ${Tables.all.size} tables")

    val transactionHelper = TransactionHelper()

    val sessionRepository = SessionRepository()
    val deletedIdsStatsRepository = DeletedIdsStatsRepository()
    val categoryRecommendationsCacheRepository = CategoryRecommendationsCacheRepository()
    val userRecommendationsCacheRepository = UserRecommendationsCacheRepository()
    val discoverSectionsRepository = com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository()
    val defaultRecommendationsCacheRepository = DefaultRecommendationsCacheRepository()
    val deviceLimitCarryoverRepository = com.lvsmsmch.aichat.user.database.DeviceLimitCarryoverRepository()
    val searchSuggestionsRepository = SearchSuggestionsRepository()
    val reviewLikeRepository = ReviewLikeRepository()
    val userRepository = UserRepository()
    val followRepository = FollowRepository()
    val reportRepository = ReportRepository()
    val characterRepository = CharacterRepository()
    val chatRepository = ChatRepository()
    val messageRepository = MessageRepository()
    val reviewRepository = ReviewRepository()
    val commentRepository = CommentRepository()
    val commentLikeRepository = CommentLikeRepository()
    val characterLikeRepository = com.lvsmsmch.aichat.character.database.CharacterLikeRepository()
    val userNotificationRepository = com.lvsmsmch.aichat.notification.database.UserNotificationRepository()
    val characterActivityLogRepository = CharacterActivityLogRepository()
    val characterListCopyRepository = CharacterListCopyRepository()
    val feedbackRepository = FeedbackRepository()
    val authCodeRepository = com.lvsmsmch.aichat.auth.database.AuthCodeRepository()
    val authLockoutRepository = com.lvsmsmch.aichat.auth.database.AuthLockoutRepository()
    // Отправщик писем: Resend, если задан ключ; иначе письма идут в лог
    val mailSender = com.lvsmsmch.aichat.mail.MailSenderFactory.create(
        HttpClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(defaultJson)
            }
        }
    )

    // Проверка токенов Apple: тем же HTTP-клиентом, что и письма
    val appleVerifier = com.lvsmsmch.aichat.auth.AppleIdentityTokenVerifier(
        HttpClient()
    )

    val cacheManager = CacheManager(
        characterRepository = characterRepository,
        userRecommendationsCacheRepository = userRecommendationsCacheRepository,
        categoryRecommendationsCacheRepository = categoryRecommendationsCacheRepository,
        defaultRecommendationsCacheRepository = defaultRecommendationsCacheRepository,
        characterListCopyRepository = characterListCopyRepository
    )

    val messageFinisher = MessageFinisher(
        messageRepository = messageRepository,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        userRepository = userRepository
    )

    val idGenerator = IdGenerator(
        deletedIdsStatsRepository = deletedIdsStatsRepository,
        userRepository = userRepository,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        reviewRepository = reviewRepository,
        commentRepository = commentRepository,
    )

    val usernameGenerator = UsernameGenerator(userRepository)

    val mapper = Mapper(
        userRepository = userRepository,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        reviewRepository = reviewRepository,
        reviewLikeRepository = reviewLikeRepository,
        followRepository = followRepository,
        characterLikeRepository = characterLikeRepository,
    )

    val notificationService = com.lvsmsmch.aichat.notification.NotificationService(
        notifications = userNotificationRepository,
        userRepository = userRepository,
        characterRepository = characterRepository,
        followRepository = followRepository,
    )

    val characterService = com.lvsmsmch.aichat.character.CharacterService(
        characterRepository = characterRepository,
        userRepository = userRepository,
        chatRepository = chatRepository,
        reviewRepository = reviewRepository,
        reviewLikeRepository = reviewLikeRepository,
        commentRepository = commentRepository,
        commentLikeRepository = commentLikeRepository,
        characterLikeRepository = characterLikeRepository,
        deletedIdsStatsRepository = deletedIdsStatsRepository,
        transactionHelper = transactionHelper,
    )

    val userService = com.lvsmsmch.aichat.user.UserService(
        userRepository = userRepository,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        reviewRepository = reviewRepository,
        reviewLikeRepository = reviewLikeRepository,
        commentRepository = commentRepository,
        commentLikeRepository = commentLikeRepository,
        characterLikeRepository = characterLikeRepository,
        followRepository = followRepository,
        sessionRepository = sessionRepository,
        userNotificationRepository = userNotificationRepository,
        deviceLimitCarryoverRepository = deviceLimitCarryoverRepository,
        deletedIdsStatsRepository = deletedIdsStatsRepository,
        transactionHelper = transactionHelper,
    )

    val commentService = com.lvsmsmch.aichat.comment.CommentService(
        commentRepository = commentRepository,
        commentLikeRepository = commentLikeRepository,
        characterRepository = characterRepository,
        characterActivityLogRepository = characterActivityLogRepository,
        deletedIdsStatsRepository = deletedIdsStatsRepository,
        userRepository = userRepository,
        notificationService = notificationService,
        transactionHelper = transactionHelper,
        idGenerator = idGenerator,
        mapper = mapper,
    )


    val chatService = com.lvsmsmch.aichat.chat.ChatService(
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        characterRepository = characterRepository,
        userRepository = userRepository,
        characterActivityLogRepository = characterActivityLogRepository,
        notificationService = notificationService,
        transactionHelper = transactionHelper,
    )

    val reviewService = com.lvsmsmch.aichat.review.ReviewService(
        reviewRepository = reviewRepository,
        reviewLikeRepository = reviewLikeRepository,
        characterRepository = characterRepository,
        characterActivityLogRepository = characterActivityLogRepository,
        deletedIdsStatsRepository = deletedIdsStatsRepository,
        transactionHelper = transactionHelper,
    )

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

    configureRouting(
        deviceLimitCarryoverRepository = deviceLimitCarryoverRepository,
        mapper = mapper,
        userRepository = userRepository,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        reviewRepository = reviewRepository,
        sessionRepository = sessionRepository,
        followRepository = followRepository,
        reportRepository = reportRepository,
        reviewLikeRepository = reviewLikeRepository,
        commentRepository = commentRepository,
        commentLikeRepository = commentLikeRepository,
        searchSuggestionsRepository = searchSuggestionsRepository,
        feedbackRepository = feedbackRepository,
        idGenerator = idGenerator,
        usernameGenerator = usernameGenerator,
        cacheManager = cacheManager,
        messageFinisher = messageFinisher,
        notificationService = notificationService,
        userNotificationRepository = userNotificationRepository,
        discoverSectionsRepository = discoverSectionsRepository,
        characterLikeRepository = characterLikeRepository,
        authCodeRepository = authCodeRepository,
        authLockoutRepository = authLockoutRepository,
        mailSender = mailSender,
        appleVerifier = appleVerifier,
        commentService = commentService,
        characterService = characterService,
        userService = userService,
        chatService = chatService,
    )

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