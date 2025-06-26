package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.db.auth.attempts.EnterLoginCodeAttemptsTracker
import com.lvsmsmch.aichat.db.auth.attempts.EnterRegistrationCodeAttemptsTracker
import com.lvsmsmch.aichat.db.auth.attempts.LoginAttemptsTracker
import com.lvsmsmch.aichat.db.auth.attempts.RegistrationAttemptsTracker
import com.lvsmsmch.aichat.db.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.auth.tokens.single_use_tokens.RegistrationCompletionTokenRepository
import com.lvsmsmch.aichat.db.auth.tokens.single_use_tokens.SetNewPasswordTokenRepository
import com.lvsmsmch.aichat.db.auth.verification_codes.LoginCodesRepository
import com.lvsmsmch.aichat.db.auth.verification_codes.RegistrationCodesRepository
import com.lvsmsmch.aichat.db.content.*
import com.lvsmsmch.aichat.auth.network.email.login.configureLoginRouting
import com.lvsmsmch.aichat.auth.network.google.configureGoogleRouting
import com.lvsmsmch.aichat.auth.network.email.login.configureVerifyLoginCodeRouting
import com.lvsmsmch.aichat.auth.network.logout.configureLogoutRouting
import com.lvsmsmch.aichat.auth.network.email.password_reset.configurePasswordResetRouting
import com.lvsmsmch.aichat.auth.network.email.password_reset.configureSetNewPasswordRouting
import com.lvsmsmch.aichat.auth.network.email.password_reset.configureVerifyPasswordResetRouting
import com.lvsmsmch.aichat.auth.network.email.register.configureCompleteRegistrationRouting
import com.lvsmsmch.aichat.auth.network.email.register.configureRegistrationRouting
import com.lvsmsmch.aichat.auth.network.email.register.configureVerifyRegistrationCodeRouting
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.character.network.configureAddCharacterRouting
import com.lvsmsmch.aichat.character.network.configureDeleteCharacterRouting
import com.lvsmsmch.aichat.user.network.configureDeleteUserRouting
import com.lvsmsmch.aichat.profile.network.configureGetMyCharacterDetailsRouting
import com.lvsmsmch.aichat.character.network.configureGetMyCharacterRouting
import com.lvsmsmch.aichat.character.network.configureGetMyCharactersRouting
import com.lvsmsmch.aichat.user.network.configureUpdateUserRouting
import com.lvsmsmch.aichat.character.network.configureUpdateCharacterRouting
import com.lvsmsmch.aichat.character.network.configureGetCharacterRouting
import com.lvsmsmch.aichat.character.network.configureGetMyCharactersDetailsRouting
import com.lvsmsmch.aichat.character.network.configureGetCharactersRouting
import com.lvsmsmch.aichat._trash.chat._old.configureChatRouting
import com.lvsmsmch.aichat.network.routing.chat.configureChatWebSocketRouting
import com.lvsmsmch.aichat._trash.chat._old.configureDeleteChatRouting
import com.lvsmsmch.aichat._trash.chat._old.configureGetChatsRouting
import com.lvsmsmch.aichat._trash.configureGetOrCreateChatRouting
import com.lvsmsmch.aichat._trash.chat._old.configureMessageHistoryRouting
import com.lvsmsmch.aichat.network.routing.chat._old.configureMessageHistorySearchRouting
import com.lvsmsmch.aichat.chat.network.configureChatsWebSocketRouting
import com.lvsmsmch.aichat._trash.chat._old.configureUpdateChatRouting
import com.lvsmsmch.aichat.review.network.configureAddReviewRouting
import com.lvsmsmch.aichat.review.network.configureDeleteReviewRouting
import com.lvsmsmch.aichat.review.network.configureGetReviewRouting
import com.lvsmsmch.aichat.review.network.configureGetReviewsRouting
import com.lvsmsmch.aichat.review.network.configureUpdateReviewRouting
import com.lvsmsmch.aichat.user.network.configureGetUserRouting
import com.lvsmsmch.aichat.user.network.configureGetUserCharactersRouting
import com.lvsmsmch.aichat.user.database.UserRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*

fun Application.configureRouting(
    mapper: Mapper,
    emailSender: EmailSender,
    userRepository: UserRepository,
    characterRepository: CharacterRepository,
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    reviewRepository: ReviewRepository,
    sessionRepository: SessionRepository,
    enterLoginCodeAttemptsTracker: EnterLoginCodeAttemptsTracker,
    enterRegistrationCodeAttemptsTracker: EnterRegistrationCodeAttemptsTracker,
    loginAttemptsTracker: LoginAttemptsTracker,
    registrationAttemptsTracker: RegistrationAttemptsTracker,
    registrationCompletionTokenRepository: RegistrationCompletionTokenRepository,
    setNewPasswordTokenRepository: SetNewPasswordTokenRepository,
    passwordResetTokenRepository: com.lvsmsmch.aichat.auth.database.tokens.verification_tokens.PasswordResetTokenRepository,
    loginCodesRepository: LoginCodesRepository,
    registrationCodesRepository: RegistrationCodesRepository
) {
    routing {
        post("/test") {
            call.respond(HttpStatusCode.OK, "Test successful")
        }

        post("/test111") {
            call.respond(HttpStatusCode.OK, "Test successful")
        }

        // Auth routes
        configureLoginRouting(
            userRepository = userRepository,
            loginAttemptsTracker = loginAttemptsTracker,
            loginCodesRepository = loginCodesRepository,
            emailSender = emailSender
        )

        configureGoogleRouting(
            userRepository = userRepository,
            sessionRepository = sessionRepository
        )

        configureVerifyLoginCodeRouting(
            userRepository = userRepository,
            enterLoginCodeAttemptsTracker = enterLoginCodeAttemptsTracker,
            loginCodesRepository = loginCodesRepository,
            sessionRepository = sessionRepository
        )

        configureLogoutRouting(
            sessionRepository = sessionRepository
        )

        configurePasswordResetRouting(
            userRepository = userRepository,
            passwordResetTokenRepository = passwordResetTokenRepository,
            emailSender = emailSender
        )

        configureSetNewPasswordRouting(
            userRepository = userRepository,
            setNewPasswordTokenRepository = setNewPasswordTokenRepository
        )

        configureVerifyPasswordResetRouting(
            passwordResetTokenRepository = passwordResetTokenRepository,
            setNewPasswordTokenRepository = setNewPasswordTokenRepository
        )

        // Registration routes
        configureCompleteRegistrationRouting(
            userRepository = userRepository,
            registrationCompletionTokensRepository = registrationCompletionTokenRepository,
            sessionRepository = sessionRepository
        )

        configureRegistrationRouting(
            userRepository = userRepository,
            registrationAttemptsTracker = registrationAttemptsTracker,
            registrationCodesRepository = registrationCodesRepository,
            emailSender = emailSender
        )

        configureVerifyRegistrationCodeRouting(
            userRepository = userRepository,
            enterRegistrationCodeAttemptsTracker = enterRegistrationCodeAttemptsTracker,
            registrationCodesRepository = registrationCodesRepository,
            registrationCompletionTokensRepository = registrationCompletionTokenRepository
        )

        // Public character routes
        configureGetCharactersRouting(
            characterRepository = characterRepository,
            mapper = mapper
        )

        configureGetCharacterRouting(
            characterRepository = characterRepository,
            mapper = mapper
        )

        configureGetMyCharactersDetailsRouting(
            characterRepository = characterRepository,
            sessionRepository = sessionRepository,
            mapper = mapper
        )

        // Profile character routes
        configureAddCharacterRouting(
            sessionRepository = sessionRepository,
            characterRepository = characterRepository,
            mapper = mapper
        )

        configureUpdateCharacterRouting(
            sessionRepository = sessionRepository,
            characterRepository = characterRepository
        )

        configureDeleteCharacterRouting(
            characterRepository = characterRepository,
            sessionRepository = sessionRepository
        )

        configureGetMyCharactersRouting(
            sessionRepository = sessionRepository,
            characterRepository = characterRepository,
            mapper = mapper
        )

        configureGetMyCharacterRouting(
            sessionRepository = sessionRepository,
            characterRepository = characterRepository,
            mapper = mapper
        )

        configureGetMyCharacterDetailsRouting(
            sessionRepository = sessionRepository,
            characterRepository = characterRepository,
            mapper = mapper
        )

        // Chat routes
        configureChatRouting(
            sessionRepository = sessionRepository,
            chatRepository = chatRepository,
            mapper = mapper
        )

        configureGetChatsRouting(
            sessionRepository = sessionRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            characterRepository = characterRepository,
            mapper = mapper
        )

        configureGetOrCreateChatRouting(
            sessionRepository = sessionRepository,
            chatRepository = chatRepository,
            characterRepository = characterRepository,
            mapper = mapper
        )

        configureDeleteChatRouting(
            sessionRepository = sessionRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository
        )

        configureUpdateChatRouting(
            sessionRepository = sessionRepository,
            chatRepository = chatRepository
        )

        // Message routes
        configureMessageHistoryRouting(
            sessionRepository = sessionRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            mapper = mapper
        )

        configureMessageHistorySearchRouting(
            sessionRepository = sessionRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            mapper = mapper
        )

        // WebSocket routes
        configureChatWebSocketRouting(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            chatRepository = chatRepository,
            mapper = mapper
        )

        configureChatsWebSocketRouting(
            sessionRepository = sessionRepository,
            chatRepository = chatRepository,
            mapper = mapper
        )

        // Profile routes
        configureUpdateUserRouting(
            userRepository = userRepository,
            sessionRepository = sessionRepository
        )

        configureDeleteUserRouting(
            userRepository = userRepository,
            sessionRepository = sessionRepository
        )

        // Review routes
        configureAddReviewRouting(
            sessionRepository = sessionRepository,
            reviewRepository = reviewRepository,
            characterRepository = characterRepository,
            mapper = mapper
        )

        configureGetReviewRouting(
            reviewRepository = reviewRepository,
            characterRepository = characterRepository,
            mapper = mapper
        )

        configureGetReviewsRouting(
            reviewRepository = reviewRepository,
            mapper = mapper
        )

        configureUpdateReviewRouting(
            sessionRepository = sessionRepository,
            characterRepository = characterRepository,
            reviewRepository = reviewRepository
        )

        configureDeleteReviewRouting(
            sessionRepository = sessionRepository,
            reviewRepository = reviewRepository,
            characterRepository = characterRepository
        )

        // User routes
        configureGetUserRouting(
            userRepository = userRepository,
            mapper = mapper
        )

        configureGetUserCharactersRouting(
            userRepository = userRepository,
            characterRepository = characterRepository,
            mapper = mapper
        )
    }
}