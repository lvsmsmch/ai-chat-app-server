package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.UsernameGenerator
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.app_data.network.configureAppDataRouting
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.auth.network.configureAuthRouting
import com.lvsmsmch.aichat.auth.network.configureAppleAuthRouting
import com.lvsmsmch.aichat.auth.network.configureEmailAuthRouting
import com.lvsmsmch.aichat.cache.CacheManager
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.character.database.SearchSuggestionsRepository
import com.lvsmsmch.aichat.character.network.configureCharacterRouting
import com.lvsmsmch.aichat.chat.MessageFinisher
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.chat.network.configureChatRouting
import com.lvsmsmch.aichat.chat.network.configureGenerationRouting
import com.lvsmsmch.aichat.chat.network.configureMessageRouting
import com.lvsmsmch.aichat.feedback.database.FeedbackRepository
import com.lvsmsmch.aichat.feedback.network.configureFeedbackRouting
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.comment.database.CommentLikeRepository
import com.lvsmsmch.aichat.comment.database.CommentRepository
import com.lvsmsmch.aichat.notification.network.configureNotificationsRouting
import com.lvsmsmch.aichat.comment.network.configureCommentRouting
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.user.network.configureUserRouting
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    // Зависимости берутся из Koin: список параметров тут не нужен, добавление
    // новой зависимости правится только в модуле di/AppModule.kt
    val mapper: Mapper by inject()
    val userRepository: UserRepository by inject()
    val characterRepository: CharacterRepository by inject()
    val chatRepository: ChatRepository by inject()
    val messageRepository: MessageRepository by inject()
    val messageRatingRepository: com.lvsmsmch.aichat.chat.database.MessageRatingRepository by inject()
    val reviewRepository: ReviewRepository by inject()
    val sessionRepository: SessionRepository by inject()
    val followRepository: FollowRepository by inject()
    val userBlockRepository: com.lvsmsmch.aichat.user.database.UserBlockRepository by inject()
    val reportRepository: ReportRepository by inject()
    val reviewLikeRepository: ReviewLikeRepository by inject()
    val commentRepository: CommentRepository by inject()
    val commentLikeRepository: CommentLikeRepository by inject()
    val searchSuggestionsRepository: SearchSuggestionsRepository by inject()
    val feedbackRepository: FeedbackRepository by inject()
    val idGenerator: IdGenerator by inject()
    val usernameGenerator: UsernameGenerator by inject()
    val cacheManager: CacheManager by inject()
    val messageFinisher: MessageFinisher by inject()
    val notificationService: com.lvsmsmch.aichat.notification.NotificationService by inject()
    val userNotificationRepository: com.lvsmsmch.aichat.notification.database.UserNotificationRepository by inject()
    val deviceLimitCarryoverRepository: com.lvsmsmch.aichat.user.database.DeviceLimitCarryoverRepository by inject()
    val discoverSectionsRepository: com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository by inject()
    val characterLikeRepository: com.lvsmsmch.aichat.character.database.CharacterLikeRepository by inject()
    val avatarGenerationLimitRepository: com.lvsmsmch.aichat.character.database.AvatarGenerationLimitRepository by inject()
    val authCodeRepository: com.lvsmsmch.aichat.auth.database.AuthCodeRepository by inject()
    val authLockoutRepository: com.lvsmsmch.aichat.auth.database.AuthLockoutRepository by inject()
    val mailSender: com.lvsmsmch.aichat.mail.MailSender by inject()
    val appleVerifier: com.lvsmsmch.aichat.auth.AppleIdentityTokenVerifier by inject()
    val commentService: com.lvsmsmch.aichat.comment.CommentService by inject()
    val characterService: com.lvsmsmch.aichat.character.CharacterService by inject()
    val userService: com.lvsmsmch.aichat.user.UserService by inject()
    val chatService: com.lvsmsmch.aichat.chat.ChatService by inject()

    routing {
        get("/test") {
            logger.info("\"test\" called!")
            call.respond(HttpStatusCode.OK, "Test successful")
        }

        // Картинки с локального диска (см. ImageServer): /images/<uuid>.jpg
        staticFiles("/images", ImageServer.imagesDir)


        rateLimit(RateLimitName("ip-based")) {
            configureAuthRouting(
                userRepository = userRepository,
                deviceLimitCarryoverRepository = deviceLimitCarryoverRepository,
                sessionRepository = sessionRepository,
                idGenerator = idGenerator,
                usernameGenerator = usernameGenerator,
                mapper = mapper,
                discoverSectionsRepository = discoverSectionsRepository,
                userService = userService,
            )

            configureEmailAuthRouting(
                userRepository = userRepository,
                sessionRepository = sessionRepository,
                authCodeRepository = authCodeRepository,
                authLockoutRepository = authLockoutRepository,
                mailSender = mailSender,
                idGenerator = idGenerator,
                usernameGenerator = usernameGenerator,
                mapper = mapper,
                discoverSectionsRepository = discoverSectionsRepository,
                userService = userService,
            )

            configureAppleAuthRouting(
                userRepository = userRepository,
                sessionRepository = sessionRepository,
                appleVerifier = appleVerifier,
                idGenerator = idGenerator,
                usernameGenerator = usernameGenerator,
                mapper = mapper,
                discoverSectionsRepository = discoverSectionsRepository,
                userService = userService,
            )

            configureAppDataRouting()

            configureUserRouting(
                characterLikeRepository = characterLikeRepository,
                userRepository = userRepository,
                sessionRepository = sessionRepository,
                followRepository = followRepository,
                characterRepository = characterRepository,
                reportRepository = reportRepository,
                mapper = mapper,
                notificationService = notificationService,
                userService = userService,
                userBlockRepository = userBlockRepository,
            )

            configureCharacterRouting(
                characterRepository = characterRepository,
                sessionRepository = sessionRepository,
                userRepository = userRepository,
                reportRepository = reportRepository,
                searchSuggestionsRepository = searchSuggestionsRepository,
                idGenerator = idGenerator,
                cacheManager = cacheManager,
                mapper = mapper,
                notificationService = notificationService,
                discoverSectionsRepository = discoverSectionsRepository,
                characterLikeRepository = characterLikeRepository,
                characterService = characterService,
                chatRepository = chatRepository,
                userBlockRepository = userBlockRepository,
                avatarGenerationLimitRepository = avatarGenerationLimitRepository,
            )

            configureCommentRouting(
                sessionRepository = sessionRepository,
                characterRepository = characterRepository,
                reportRepository = reportRepository,
                commentService = commentService,
            )

            configureNotificationsRouting(
                sessionRepository = sessionRepository,
                userNotificationRepository = userNotificationRepository,
                userRepository = userRepository,
                characterRepository = characterRepository,
                commentRepository = commentRepository,
            )

            configureChatRouting(
                chatRepository = chatRepository,
                messageRepository = messageRepository,
                characterRepository = characterRepository,
                sessionRepository = sessionRepository,
                idGenerator = idGenerator,
                messageFinisher = messageFinisher,
                reportRepository = reportRepository,
                mapper = mapper,
                userRepository = userRepository,
                chatService = chatService,
            )

            configureMessageRouting(
                chatRepository = chatRepository,
                messageRepository = messageRepository,
                messageRatingRepository = messageRatingRepository,
                characterRepository = characterRepository,
                sessionRepository = sessionRepository,
                idGenerator = idGenerator,
                messageFinisher = messageFinisher,
                reportRepository = reportRepository,
                mapper = mapper,
                userRepository = userRepository,
                chatService = chatService,
            )

            configureGenerationRouting(
                chatRepository = chatRepository,
                messageRepository = messageRepository,
                characterRepository = characterRepository,
                sessionRepository = sessionRepository,
                idGenerator = idGenerator,
                messageFinisher = messageFinisher,
                reportRepository = reportRepository,
                mapper = mapper,
                userRepository = userRepository,
                chatService = chatService,
            )

            configureFeedbackRouting(
                feedbackRepository = feedbackRepository,
                sessionRepository = sessionRepository
            )
        }
    }
}
