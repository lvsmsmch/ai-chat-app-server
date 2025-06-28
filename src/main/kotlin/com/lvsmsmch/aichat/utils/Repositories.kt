package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat._common.database.EntityIdStatsRepository
import com.lvsmsmch.aichat._common.database.EntityIdStatsDbo
import com.lvsmsmch.aichat.auth.database.attempts.*
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionDbo
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat._trash.single_use_tokens.RegistrationCompletionTokenDbo
import com.lvsmsmch.aichat._trash.single_use_tokens.RegistrationCompletionTokenRepository
import com.lvsmsmch.aichat._trash.single_use_tokens.SetNewPasswordTokenDbo
import com.lvsmsmch.aichat._trash.single_use_tokens.SetNewPasswordTokenRepository
import com.lvsmsmch.aichat._trash.PasswordResetTokenRepository
import com.lvsmsmch.aichat._trash.VerificationTokenDbo
import com.lvsmsmch.aichat._trash.verification_codes.LoginCodesRepository
import com.lvsmsmch.aichat._trash.verification_codes.RegistrationCodesRepository
import com.lvsmsmch.aichat._trash.verification_codes.VerificationCodeDbo
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.chat.database.ChatDbo
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageDbo
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat._common.database.ReportDbo
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat._trash.attempts.*
import com.lvsmsmch.aichat.cache.database.DefaultRecommendationsCacheDbo
import com.lvsmsmch.aichat.cache.database.DefaultRecommendationsCacheRepository
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.database.FollowDbo
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.user.database.UserRepository
import org.litote.kmongo.coroutine.CoroutineDatabase

fun configureEntityIdStatsRepository(database: CoroutineDatabase): EntityIdStatsRepository {
    return EntityIdStatsRepository(database.getCollection<EntityIdStatsDbo>("entity_id_stats"))
}

fun configureUserRepository(database: CoroutineDatabase): UserRepository {
    return UserRepository(database.getCollection<UserDbo>("users"))
}

fun configureFollowRepository(database: CoroutineDatabase): FollowRepository {
    return FollowRepository(database.getCollection<FollowDbo>("follows"))
}

fun configureReportRepository(database: CoroutineDatabase): ReportRepository {
    return ReportRepository(database.getCollection<ReportDbo>("reports"))
}

fun configureCharacterRepository(database: CoroutineDatabase): CharacterRepository {
    return CharacterRepository(database.getCollection<CharacterDbo>("characters"))
}

fun configureChatRepository(database: CoroutineDatabase): ChatRepository {
    return ChatRepository(database.getCollection<ChatDbo>("chats"))
}

fun configureMessageRepository(database: CoroutineDatabase): MessageRepository {
    return MessageRepository(database.getCollection<MessageDbo>("messages"))
}

fun configureReviewRepository(database: CoroutineDatabase): ReviewRepository {
    return ReviewRepository(database.getCollection<ReviewDbo>("reviews"))
}

fun configureCharacterActivityLogRepository(database: CoroutineDatabase): CharacterActivityLogRepository {
    return CharacterActivityLogRepository(
        database.getCollection<CharacterActivityLogDbo>("character_activity_logs")
    )
}

fun configureDefaultRecommendationsRepository(database: CoroutineDatabase): DefaultRecommendationsCacheRepository {
    return DefaultRecommendationsCacheRepository(
        database.getCollection<DefaultRecommendationsCacheDbo>("default_recommendations"),
    )
}

fun configureSessionRepository(database: CoroutineDatabase): SessionRepository {
    return SessionRepository(database.getCollection<SessionDbo>("sessions"))
}