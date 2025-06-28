package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.app_data.network.configureAppDataRouting
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.auth.network.configureAuthRouting
import com.lvsmsmch.aichat.cache.CacheManager
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.character.database.SearchSuggestionsRepository
import com.lvsmsmch.aichat.character.network.configureCharacterRouting
import com.lvsmsmch.aichat.chat.MessageFinisher
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.chat.network.configureChatRouting
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.review.network.configureReviewRouting
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.user.network.configureUserRouting
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
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
    characterActivityLogRepository: CharacterActivityLogRepository,
    searchSuggestionsRepository: SearchSuggestionsRepository,
    idGenerator: IdGenerator,
    cacheManager: CacheManager,
    messageFinisher: MessageFinisher
) {
    routing {
        // Базовые тестовые эндпоинты
        post("/test") {
            call.respond(HttpStatusCode.OK, "Test successful")
        }

        post("/test111") {
            call.respond(HttpStatusCode.OK, "Test successful")
        }

        // Конфигурация всех роутингов
        configureAppDataRouting()

        configureAuthRouting(
            userRepository = userRepository,
            sessionRepository = sessionRepository,
            idGenerator = idGenerator,
            mapper = mapper
        )

        configureCharacterRouting(
            characterRepository = characterRepository,
            sessionRepository = sessionRepository,
            userRepository = userRepository,
            reportRepository = reportRepository,
            searchSuggestionsRepository = searchSuggestionsRepository,
            idGenerator = idGenerator,
            cacheManager = cacheManager,
            mapper = mapper
        )

        configureChatRouting(
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            characterRepository = characterRepository,
            sessionRepository = sessionRepository,
            idGenerator = idGenerator,
            messageFinisher = messageFinisher,
            mapper = mapper
        )

        configureReviewRouting(
            sessionRepository = sessionRepository,
            reviewRepository = reviewRepository,
            reviewLikeRepository = reviewLikeRepository,
            characterRepository = characterRepository,
            characterActivityLogRepository = characterActivityLogRepository,
            reportRepository = reportRepository,
            userRepository = userRepository,
            idGenerator = idGenerator,
            mapper = mapper
        )

        configureUserRouting(
            userRepository = userRepository,
            sessionRepository = sessionRepository,
            followRepository = followRepository,
            characterRepository = characterRepository,
            reportRepository = reportRepository,
            mapper = mapper
        )
    }
}