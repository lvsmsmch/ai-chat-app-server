package com.lvsmsmch.aichat.di

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.UsernameGenerator
import com.lvsmsmch.aichat._common.database.DeletedIdsStatsRepository
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.auth.AppleIdentityTokenVerifier
import com.lvsmsmch.aichat.auth.database.AuthCodeRepository
import com.lvsmsmch.aichat.auth.database.AuthLockoutRepository
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.cache.CacheManager
import com.lvsmsmch.aichat.cache.database.CategoryRecommendationsCacheRepository
import com.lvsmsmch.aichat.cache.database.CharacterListCopyRepository
import com.lvsmsmch.aichat.cache.database.DefaultRecommendationsCacheRepository
import com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository
import com.lvsmsmch.aichat.cache.database.UserRecommendationsCacheRepository
import com.lvsmsmch.aichat.character.CharacterService
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterLikeRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.character.database.SearchSuggestionsRepository
import com.lvsmsmch.aichat.chat.ChatService
import com.lvsmsmch.aichat.chat.MessageFinisher
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.comment.CommentService
import com.lvsmsmch.aichat.comment.database.CommentLikeRepository
import com.lvsmsmch.aichat.comment.database.CommentRepository
import com.lvsmsmch.aichat.feedback.database.FeedbackRepository
import com.lvsmsmch.aichat.jobs.BackgroundJobs
import com.lvsmsmch.aichat.mail.MailSender
import com.lvsmsmch.aichat.mail.MailSenderFactory
import com.lvsmsmch.aichat.notification.NotificationService
import com.lvsmsmch.aichat.notification.database.UserNotificationRepository
import com.lvsmsmch.aichat.review.ReviewService
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.UserService
import com.lvsmsmch.aichat.user.database.DeviceLimitCarryoverRepository
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.Mapper
import com.lvsmsmch.aichat.utils.TransactionHelper
import com.lvsmsmch.aichat.utils.defaultJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Единственное место, где собирается граф зависимостей.
 *
 * До этого всё связывалось руками в `Application.kt`: добавление одной
 * зависимости правилось в трёх местах — объявление, параметр `configureRouting`
 * и передача в вызове. Теперь достаточно строки здесь.
 *
 * Всё — `single`: репозитории и сервисы без состояния, держать их по одному на
 * процесс правильно и дёшево.
 */
val appModule = module {

    // ---- инфраструктура ----
    single { TransactionHelper() }
    single(named("mailHttpClient")) {
        HttpClient { install(ContentNegotiation) { json(defaultJson) } }
    }
    single<MailSender> { MailSenderFactory.create(get(named("mailHttpClient"))) }
    single { AppleIdentityTokenVerifier(HttpClient()) }

    // ---- репозитории ----
    single { SessionRepository() }
    single { DeletedIdsStatsRepository() }
    single { CategoryRecommendationsCacheRepository() }
    single { UserRecommendationsCacheRepository() }
    single { DiscoverSectionsCacheRepository() }
    single { DefaultRecommendationsCacheRepository() }
    single { DeviceLimitCarryoverRepository() }
    single { SearchSuggestionsRepository() }
    single { ReviewLikeRepository() }
    single { UserRepository() }
    single { FollowRepository() }
    single { com.lvsmsmch.aichat.user.database.UserBlockRepository() }
    single { ReportRepository() }
    single { CharacterRepository() }
    single { ChatRepository() }
    single { MessageRepository() }
    single { com.lvsmsmch.aichat.chat.database.MessageRatingRepository() }
    single { ReviewRepository() }
    single { CommentRepository() }
    single { CommentLikeRepository() }
    single { CharacterLikeRepository() }
    single { UserNotificationRepository() }
    single { CharacterActivityLogRepository() }
    single { CharacterListCopyRepository() }
    single { FeedbackRepository() }
    single { AuthCodeRepository() }
    single { AuthLockoutRepository() }

    // ---- общее ----
    single {
        IdGenerator(
            deletedIdsStatsRepository = get(),
            userRepository = get(),
            characterRepository = get(),
            chatRepository = get(),
            messageRepository = get(),
            reviewRepository = get(),
            commentRepository = get(),
        )
    }
    single { UsernameGenerator(get()) }
    single {
        Mapper(
            userRepository = get(),
            characterRepository = get(),
            chatRepository = get(),
            messageRepository = get(),
            reviewRepository = get(),
            reviewLikeRepository = get(),
            followRepository = get(),
            characterLikeRepository = get(),
            userBlockRepository = get(),
        )
    }
    single {
        CacheManager(
            characterRepository = get(),
            userRecommendationsCacheRepository = get(),
            categoryRecommendationsCacheRepository = get(),
            defaultRecommendationsCacheRepository = get(),
            characterListCopyRepository = get(),
        )
    }
    single {
        MessageFinisher(
            messageRepository = get(),
            characterRepository = get(),
            chatRepository = get(),
            userRepository = get(),
        )
    }
    single {
        NotificationService(
            notifications = get(),
            userRepository = get(),
            characterRepository = get(),
            followRepository = get(),
        )
    }

    // ---- фоновые задачи ----
    single {
        BackgroundJobs(
            userRepository = get(),
            characterRepository = get(),
            chatRepository = get(),
            messageRepository = get(),
            characterActivityLogRepository = get(),
            userRecommendationsCacheRepository = get(),
            categoryRecommendationsCacheRepository = get(),
            defaultRecommendationsCacheRepository = get(),
            discoverSectionsRepository = get(),
            searchSuggestionsRepository = get(),
            authCodeRepository = get(),
            idGenerator = get(),
            usernameGenerator = get(),
            userService = get(),
        )
    }

    // ---- сервисы доменов ----
    single {
        CommentService(
            commentRepository = get(),
            commentLikeRepository = get(),
            characterRepository = get(),
            characterActivityLogRepository = get(),
            deletedIdsStatsRepository = get(),
            userRepository = get(),
            notificationService = get(),
            transactionHelper = get(),
            idGenerator = get(),
            mapper = get(),
        )
    }
    single {
        CharacterService(
            characterRepository = get(),
            userRepository = get(),
            chatRepository = get(),
            reviewRepository = get(),
            reviewLikeRepository = get(),
            commentRepository = get(),
            commentLikeRepository = get(),
            characterLikeRepository = get(),
            deletedIdsStatsRepository = get(),
            transactionHelper = get(),
        )
    }
    single {
        UserService(
            userRepository = get(),
            characterRepository = get(),
            chatRepository = get(),
            messageRepository = get(),
            reviewRepository = get(),
            reviewLikeRepository = get(),
            commentRepository = get(),
            commentLikeRepository = get(),
            characterLikeRepository = get(),
            followRepository = get(),
            userBlockRepository = get(),
            sessionRepository = get(),
            userNotificationRepository = get(),
            deviceLimitCarryoverRepository = get(),
            deletedIdsStatsRepository = get(),
            transactionHelper = get(),
        )
    }
    single {
        ChatService(
            chatRepository = get(),
            messageRepository = get(),
            characterRepository = get(),
            userRepository = get(),
            characterActivityLogRepository = get(),
            notificationService = get(),
            transactionHelper = get(),
        )
    }
    single {
        ReviewService(
            reviewRepository = get(),
            reviewLikeRepository = get(),
            characterRepository = get(),
            characterActivityLogRepository = get(),
            deletedIdsStatsRepository = get(),
            transactionHelper = get(),
        )
    }
}
