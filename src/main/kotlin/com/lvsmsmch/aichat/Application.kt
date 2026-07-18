package com.lvsmsmch.aichat

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
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.*
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
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
            rateLimiter(limit = 100, refillPeriod = 1.minutes)
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

    val mongoClient = KMongo.createClient(
        System.getenv("MONGODB_URI") ?: "mongodb://localhost:27017"
    ).coroutine

    val database = mongoClient.getDatabase("ai_chat_app_database")

    val transactionHelper = TransactionHelper(mongoClient)

    val sessionRepository = SessionRepository(
        database.getCollection<SessionDbo>("sessions")
    )
    val deletedIdsStatsRepository = DeletedIdsStatsRepository(
        database.getCollection<DeletedIdsStatsDbo>("entity_id_stats")
    )
    val categoryRecommendationsCacheRepository = CategoryRecommendationsCacheRepository(
        database.getCollection<CategoryRecommendationsCacheDbo>("category_cache")
    )
    val userRecommendationsCacheRepository = UserRecommendationsCacheRepository(
        database.getCollection<UserRecommendationsCacheDbo>("user_recommendations_cache")
    )
    val defaultRecommendationsCacheRepository = DefaultRecommendationsCacheRepository(
        database.getCollection<DefaultRecommendationsCacheDbo>("default_personalized_cache")
    )
    val searchSuggestionsRepository = SearchSuggestionsRepository(
        database.getCollection<SearchSuggestionDbo>("search_suggestions")
    )
    val reviewLikeRepository = ReviewLikeRepository(
        database.getCollection<ReviewLikeDbo>("review_likes")
    )
    val userRepository = UserRepository(
        database.getCollection<UserDbo>("users")
    )
    val followRepository = FollowRepository(
        database.getCollection<FollowDbo>("follows")
    )
    val reportRepository = ReportRepository(
        database.getCollection<ReportDbo>("reports")
    )
    val characterRepository = CharacterRepository(
        database.getCollection<CharacterDbo>("characters")
    )
    val chatRepository = ChatRepository(
        database.getCollection<ChatDbo>("chats")
    )
    val messageRepository = MessageRepository(
        database.getCollection<MessageDbo>("messages")
    )
    val reviewRepository = ReviewRepository(
        database.getCollection<ReviewDbo>("reviews")
    )
    val characterActivityLogRepository = CharacterActivityLogRepository(
        database.getCollection<CharacterActivityLogDbo>("character_activity_logs")
    )
    val characterListCopyRepository = CharacterListCopyRepository(
        database.getCollection<CharacterListCopyDbo>("character_list_copy_dbo")
    )
    val feedbackRepository = FeedbackRepository(
        database.getCollection<FeedbackDbo>("feedbacks")
    )

    databaseScope.launch {
        try {
            sessionRepository.ensureIndexes()
            userRepository.ensureIndexes()
            followRepository.ensureIndexes()
            reportRepository.ensureIndexes()
            characterRepository.ensureIndexes()
            searchSuggestionsRepository.ensureIndexes()
            chatRepository.ensureIndexes()
            messageRepository.ensureIndexes()
            reviewRepository.ensureIndexes()
            reviewLikeRepository.ensureIndexes()
            feedbackRepository.ensureIndexes()
            logger.info("Database indexes ensured")
        } catch (e: Exception) {
            logger.error("Failed to ensure database indexes: ${e.message}", e)
        }
    }

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
        chatRepository = chatRepository
    )

    val idGenerator = IdGenerator(
        deletedIdsStatsRepository = deletedIdsStatsRepository,
        userRepository = userRepository,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        reviewRepository = reviewRepository,
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
    )

    val complexQueryHelper = ComplexQueryHelper(
        transactionHelper = transactionHelper,
        userRepository = userRepository,
        characterRepository = characterRepository,
        reviewRepository = reviewRepository,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        followRepository = followRepository,
        searchSuggestionsRepository = searchSuggestionsRepository,
        reviewLikeRepository = reviewLikeRepository,
        deletedIdsStatsRepository = deletedIdsStatsRepository,
        characterActivityLogRepository = characterActivityLogRepository
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

    val fillDefaultSuggestionsJob = fillDefaultSuggestions(
        databaseScope = databaseScope,
        searchSuggestionsRepository = searchSuggestionsRepository
    )

    val fillInitialDataJob = fillInitialData(
        databaseScope = databaseScope,
        userRepository = userRepository,
        idGenerator = idGenerator,
        usernameGenerator = usernameGenerator,
        complexQueryHelper = complexQueryHelper
    )

    configureRouting(
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
        searchSuggestionsRepository = searchSuggestionsRepository,
        feedbackRepository = feedbackRepository,
        idGenerator = idGenerator,
        usernameGenerator = usernameGenerator,
        cacheManager = cacheManager,
        messageFinisher = messageFinisher,
        complexQueryHelper = complexQueryHelper
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
            hourlyCounterUpdaterJob.cancelAndJoin()
            dailyCounterUpdaterJob.cancelAndJoin()
            fillDefaultSuggestionsJob.cancelAndJoin()
            fillInitialDataJob.cancelAndJoin()

            databaseScope.cancel()
            logger.info("All repository connections have been cancelled")
        }
    }
}