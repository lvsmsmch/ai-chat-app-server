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
import com.lvsmsmch.aichat.db.repositories.content.CharactersRepository
import com.lvsmsmch.aichat.db.repositories.content.ReviewsRepository
import com.lvsmsmch.aichat.db.repositories.content.UserDbo
import com.lvsmsmch.aichat.db.repositories.content.UsersRepository
import org.litote.kmongo.coroutine.CoroutineDatabase

class Repositories(
    val usersRepository: UsersRepository,
    val charactersRepository: CharactersRepository,
    val reviewsRepository: ReviewsRepository,
    val authTokensForSessionRepository: AuthTokensForSessionRepository,
    val authTokensForResetPasswordRepository: AuthTokensForResetPasswordRepository,
    val authTokensForRegisterRepository: AuthTokensForRegisterRepository,
    val codeTrackingsForVerifyEmailRepository: CodeTrackingsForVerifyEmailRepository,
    val codeTrackingsForRestorePasswordRepository: CodeTrackingsForRestorePasswordRepository,
    val loginAttemptTracker: LoginAttemptTracker,
)

fun configureRepositories(database: CoroutineDatabase): Repositories {
    return Repositories(
        usersRepository = UsersRepository(
            database.getCollection<UserDbo>("users")
        ),
        charactersRepository = CharactersRepository(
            database.getCollection<CharacterDbo>("characters")
        ),
        reviewsRepository = ReviewsRepository(
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