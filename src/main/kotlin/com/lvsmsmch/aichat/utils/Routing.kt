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

fun Application.configureRouting(
    mapper: Mapper,
    userRepository: UserRepository,
    characterRepository: CharacterRepository,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    reviewRepository: ReviewRepository,
    sessionRepository: SessionRepository,
    followRepository: FollowRepository,
    reportRepository: ReportRepository,
    reviewLikeRepository: ReviewLikeRepository,
    commentRepository: CommentRepository,
    commentLikeRepository: CommentLikeRepository,
    searchSuggestionsRepository: SearchSuggestionsRepository,
    feedbackRepository: FeedbackRepository,
    idGenerator: IdGenerator,
    usernameGenerator: UsernameGenerator,
    cacheManager: CacheManager,
    messageFinisher: MessageFinisher,
    complexQueryHelper: ComplexQueryHelper,
    notificationService: com.lvsmsmch.aichat.notification.NotificationService,
    userNotificationRepository: com.lvsmsmch.aichat.notification.database.UserNotificationRepository,
    deviceLimitCarryoverRepository: com.lvsmsmch.aichat.user.database.DeviceLimitCarryoverRepository,
    discoverSectionsRepository: com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository,
    characterLikeRepository: com.lvsmsmch.aichat.character.database.CharacterLikeRepository,
    authCodeRepository: com.lvsmsmch.aichat.auth.database.AuthCodeRepository,
    authLockoutRepository: com.lvsmsmch.aichat.auth.database.AuthLockoutRepository,
    mailSender: com.lvsmsmch.aichat.mail.MailSender,
    appleVerifier: com.lvsmsmch.aichat.auth.AppleIdentityTokenVerifier,
) {
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
                complexQueryHelper = complexQueryHelper,
                discoverSectionsRepository = discoverSectionsRepository,
            )

            configureEmailAuthRouting(
                userRepository = userRepository,
                sessionRepository = sessionRepository,
                authCodeRepository = authCodeRepository,
                authLockoutRepository = authLockoutRepository,
                mailSender = mailSender,
                idGenerator = idGenerator,
                usernameGenerator = usernameGenerator,
                complexQueryHelper = complexQueryHelper,
                mapper = mapper,
                discoverSectionsRepository = discoverSectionsRepository,
            )

            configureAppleAuthRouting(
                userRepository = userRepository,
                sessionRepository = sessionRepository,
                appleVerifier = appleVerifier,
                idGenerator = idGenerator,
                usernameGenerator = usernameGenerator,
                complexQueryHelper = complexQueryHelper,
                mapper = mapper,
                discoverSectionsRepository = discoverSectionsRepository,
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
                complexQueryHelper = complexQueryHelper,
                notificationService = notificationService,
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
                complexQueryHelper = complexQueryHelper,
                notificationService = notificationService,
                discoverSectionsRepository = discoverSectionsRepository,
                characterLikeRepository = characterLikeRepository,
            )

            configureCommentRouting(
                sessionRepository = sessionRepository,
                commentRepository = commentRepository,
                commentLikeRepository = commentLikeRepository,
                characterRepository = characterRepository,
                reportRepository = reportRepository,
                userRepository = userRepository,
                idGenerator = idGenerator,
                mapper = mapper,
                complexQueryHelper = complexQueryHelper,
                notificationService = notificationService,
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
                complexQueryHelper = complexQueryHelper,
                userRepository = userRepository
            )

            configureFeedbackRouting(
                feedbackRepository = feedbackRepository,
                sessionRepository = sessionRepository
            )
        }
    }
}