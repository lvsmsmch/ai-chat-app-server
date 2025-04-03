package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.db.repositories.auth.attempts.LoginAttemptDbo
import com.lvsmsmch.aichat.db.repositories.auth.attempts.LoginAttemptTracker
import com.lvsmsmch.aichat.db.repositories.session.single_use.AuthTokensForRegisterRepository
import com.lvsmsmch.aichat.db.repositories.session.single_use.AuthTokensForResetPasswordRepository
import com.lvsmsmch.aichat.db.repositories.session.single_use.SingleUseAuthTokenDbo
import com.lvsmsmch.aichat.db.repositories.session.session.AuthTokensForSessionRepository
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionDbo
import com.lvsmsmch.aichat.db.repositories._trash.code_trackings.CodeTrackingDbo
import com.lvsmsmch.aichat.db.repositories._trash.code_trackings.CodeTrackingsForRestorePasswordRepository
import com.lvsmsmch.aichat.db.repositories._trash.code_trackings.CodeTrackingsForVerifyEmailRepository
import com.lvsmsmch.aichat.db.repositories.content.CharacterDbo
import com.lvsmsmch.aichat.db.repositories.content.CharacterRepository
import com.lvsmsmch.aichat.db.repositories.content.ReviewRepository
import com.lvsmsmch.aichat.db.repositories.content.UserDbo
import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import org.litote.kmongo.coroutine.CoroutineDatabase

class Repositories(
    val userRepository: UserRepository,
    val characterRepository: CharacterRepository,
    val reviewRepository: ReviewRepository,
    val authTokensForSessionRepository: AuthTokensForSessionRepository,
    val authTokensForResetPasswordRepository: AuthTokensForResetPasswordRepository,
    val authTokensForRegisterRepository: AuthTokensForRegisterRepository,
    val codeTrackingsForVerifyEmailRepository: CodeTrackingsForVerifyEmailRepository,
    val codeTrackingsForRestorePasswordRepository: CodeTrackingsForRestorePasswordRepository,
    val loginAttemptTracker: LoginAttemptTracker,
)

fun configureRepositories(database: CoroutineDatabase): Repositories {
    return Repositories(
        userRepository = UserRepository(
            database.getCollection<UserDbo>("users")
        ),
        characterRepository = CharacterRepository(
            database.getCollection<CharacterDbo>("characters")
        ),
        reviewRepository = ReviewRepository(
            database.getCollection<CharacterDbo>("characters")
        ),
        authTokensForSessionRepository = AuthTokensForSessionRepository(
            database.getCollection<SessionDbo>("auth_tokens_for_sessions")
        ),
        authTokensForResetPasswordRepository = AuthTokensForResetPasswordRepository(
            database.getCollection<SingleUseAuthTokenDbo>("auth_tokens_for_set_new_password")
        ),
        authTokensForRegisterRepository = AuthTokensForRegisterRepository(
            database.getCollection<SingleUseAuthTokenDbo>("auth_tokens_for_create_password")
        ),
        codeTrackingsForVerifyEmailRepository = CodeTrackingsForVerifyEmailRepository(
            database.getCollection<CodeTrackingDbo>("code_trackings_for_verify_email")
        ),
        codeTrackingsForRestorePasswordRepository = CodeTrackingsForRestorePasswordRepository(
            database.getCollection<CodeTrackingDbo>("code_trackings_for_restore_password")
        ),
        loginAttemptTracker = LoginAttemptTracker(
            database.getCollection<LoginAttemptDbo>("attempts_for_login")
        )
    )
}