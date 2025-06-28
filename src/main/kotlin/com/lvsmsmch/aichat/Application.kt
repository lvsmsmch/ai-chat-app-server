package com.lvsmsmch.aichat

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.EntityIdStatsDbo
import com.lvsmsmch.aichat._common.database.EntityIdStatsRepository
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
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.*
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import java.time.Duration

fun main() {
    embeddedServer(Netty, port = 8080) {
        module()
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
    install(CorrelationIdPlugin)
    configureErrorHandling()

    val databaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database = KMongo.createClient("mongodb://localhost:27017").coroutine
        .getDatabase("ai_chat_app_database")

    val sessionRepository = SessionRepository(
        database.getCollection<SessionDbo>("sessions")
    )
    val entityIdStatsRepository = EntityIdStatsRepository(
        database.getCollection<EntityIdStatsDbo>("entity_id_stats")
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
        entityIdStatsRepository = entityIdStatsRepository,
        userRepository = userRepository,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        reviewRepository = reviewRepository,
    )

    val mapper = Mapper(
        userRepository = userRepository,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        reviewRepository = reviewRepository,
        reviewLikeRepository = reviewLikeRepository,
        followRepository = followRepository,
    )

    val repositoriesConnectionsJob = configureRepositoriesConnections(
        logger = log,
        databaseScope = databaseScope,
        entityIdStatsRepository = entityIdStatsRepository,
        userRepository = userRepository,
        followRepository = followRepository,
        reportRepository = reportRepository,
        characterRepository = characterRepository,
        characterActivityLogRepository = characterActivityLogRepository,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        reviewRepository = reviewRepository,
        defaultRecommendationsCacheRepository = defaultRecommendationsCacheRepository,
        searchSuggestionsRepository = searchSuggestionsRepository,
        reviewLikeRepository = reviewLikeRepository
    )

    val characterTrendingScoreUpdaterJob = configureCharacterTrendingScoreUpdater(
        logger = log,
        databaseScope = databaseScope,
        characterRepository = characterRepository,
        characterActivityLogRepository = characterActivityLogRepository,
        updateIntervalMinutes = 60,
        includeIntervalMinutes = 60 * 24 * 7
    )

    val recommendationScoreUpdaterJob = configureCharacterRecommendationScoreUpdater(
        logger = log,
        databaseScope = databaseScope,
        characterRepository = characterRepository,
        updateIntervalMinutes = 60
    )

    val coOccurrenceScoreUpdaterJob = configureCharacterCoOccurrenceUpdater(
        logger = log,
        databaseScope = databaseScope,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        updateIntervalMinutes = 60
    )

    val userRecommendationsUpdaterJob = configureUserRecommendationsUpdater(
        logger = log,
        databaseScope = databaseScope,
        userRepository = userRepository,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        userCacheRepository = userRecommendationsCacheRepository,
        updateIntervalMinutes = 60
    )

    val categoryCacheUpdaterJob = configureCategoryRecommendationsUpdater(
        logger = log,
        databaseScope = databaseScope,
        characterRepository = characterRepository,
        categoryRecommendationsCacheRepository = categoryRecommendationsCacheRepository,
        updateIntervalMinutes = 180 // каждые 3 часа
    )

    val defaultPersonalizedUpdaterJob = configureDefaultRecommendationsUpdater(
        logger = log,
        databaseScope = databaseScope,
        characterRepository = characterRepository,
        defaultRecommendationsCacheRepository = defaultRecommendationsCacheRepository,
        updateIntervalMinutes = 60 // каждый час
    )

    fillDefaultSuggestions(
        logger = log,
        databaseScope = databaseScope,
        searchSuggestionsRepository = searchSuggestionsRepository
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
        characterActivityLogRepository = characterActivityLogRepository,
        searchSuggestionsRepository = searchSuggestionsRepository,
        idGenerator = idGenerator,
        cacheManager = cacheManager,
        messageFinisher = messageFinisher
    )

    environment.monitor.subscribe(ApplicationStopping) {
        runBlocking {
            log.info("Application stopping, cancelling repository connections...")
            repositoriesConnectionsJob.cancelAndJoin()
            characterTrendingScoreUpdaterJob.cancelAndJoin()
            recommendationScoreUpdaterJob.cancelAndJoin()
            coOccurrenceScoreUpdaterJob.cancelAndJoin()
            userRecommendationsUpdaterJob.cancelAndJoin()
            categoryCacheUpdaterJob.cancelAndJoin()
            defaultPersonalizedUpdaterJob.cancelAndJoin()

            databaseScope.cancel()
            log.info("All repository connections have been cancelled")
        }
    }
}