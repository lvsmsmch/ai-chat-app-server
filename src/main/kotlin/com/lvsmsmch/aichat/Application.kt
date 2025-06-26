package com.lvsmsmch.aichat

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.EntityIdStatsDbo
import com.lvsmsmch.aichat._common.database.EntityIdStatsRepository
import com.lvsmsmch.aichat.cache.database.*
import com.lvsmsmch.aichat.character.database.SearchSuggestionDbo
import com.lvsmsmch.aichat.character.database.SearchSuggestionsRepository
import com.lvsmsmch.aichat.review.database.ReviewLikeDbo
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
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

    val emailSender = EmailSender()

    // ========== DATABASE ==========

    val databaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database = KMongo.createClient("mongodb://localhost:27017").coroutine
        .getDatabase("ai_chat_app_database")

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

    val userRepository = configureUserRepository(database)
    val followRepository = configureFollowRepository(database)
    val reportRepository = configureReportRepository(database)
    val characterRepository = configureCharacterRepository(database)
    val chatRepository = configureChatRepository(database)
    val messageRepository = configureMessageRepository(database)
    val reviewRepository = configureReviewRepository(database)
    val sessionRepository = configureSessionRepository(database)
    val characterActivityLogRepository = configureCharacterActivityLogRepository(database)
    val defaultRecommendationsRepository = configureDefaultRecommendationsRepository(database)
    val enterLoginCodeAttemptsTracker = configureEnterLoginCodeAttemptsTracker(database)
    val enterRegistrationCodeAttemptsTracker = configureEnterRegistrationCodeAttemptsTracker(database)
    val loginAttemptsTracker = configureLoginAttemptsTracker(database)
    val registrationAttemptsTracker = configureRegistrationAttemptsTracker(database)
    val registrationCompletionTokenRepository = configureRegistrationCompletionTokenRepository(database)
    val setNewPasswordTokenRepository = configureSetNewPasswordTokenRepository(database)
    val passwordResetTokenRepository = configurePasswordResetTokenRepository(database)
    val loginCodesRepository = configureLoginCodesRepository(database)
    val registrationCodesRepository = configureRegistrationCodesRepository(database)

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
        defaultRecommendationsRepository = defaultRecommendationsRepository,
        searchSuggestionsRepository = searchSuggestionsRepository
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
        emailSender = emailSender,
        userRepository = userRepository,
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        reviewRepository = reviewRepository,
        sessionRepository = sessionRepository,
        enterLoginCodeAttemptsTracker = enterLoginCodeAttemptsTracker,
        enterRegistrationCodeAttemptsTracker = enterRegistrationCodeAttemptsTracker,
        loginAttemptsTracker = loginAttemptsTracker,
        registrationAttemptsTracker = registrationAttemptsTracker,
        registrationCompletionTokenRepository = registrationCompletionTokenRepository,
        setNewPasswordTokenRepository = setNewPasswordTokenRepository,
        passwordResetTokenRepository = passwordResetTokenRepository,
        loginCodesRepository = loginCodesRepository,
        registrationCodesRepository = registrationCodesRepository,
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