package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.UsernameGenerator
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.app_data.network.configureAppDataRouting
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.auth.network.configureAuthRouting
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
                sessionRepository = sessionRepository,
                idGenerator = idGenerator,
                usernameGenerator = usernameGenerator,
                mapper = mapper,
                complexQueryHelper = complexQueryHelper
            )

            configureAppDataRouting()

            configureUserRouting(
                userRepository = userRepository,
                sessionRepository = sessionRepository,
                followRepository = followRepository,
                characterRepository = characterRepository,
                reportRepository = reportRepository,
                mapper = mapper,
                complexQueryHelper = complexQueryHelper,
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
                complexQueryHelper = complexQueryHelper
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