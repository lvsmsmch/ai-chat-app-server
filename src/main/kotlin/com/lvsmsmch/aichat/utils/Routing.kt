package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.network.routing.admin.configureMongoDBStructureRouting
import com.lvsmsmch.aichat.network.routing.characters.configureAddCharacterRouting
import com.lvsmsmch.aichat.network.routing.characters.configureDeleteCharacterRouting
import com.lvsmsmch.aichat.network.routing.characters.configureGetCharactersRouting
import com.lvsmsmch.aichat.network.routing.reviews.configureAddReviewRouting
import com.lvsmsmch.aichat.network.routing.reviews.configureDeleteReviewRouting
import com.lvsmsmch.aichat.network.routing.reviews.configureGetReviewsRouting
import com.lvsmsmch.aichat.network.routing.reviews.configureUpdateReviewRouting
import com.lvsmsmch.aichat.network.routing.users.configureGetUserByIdRouting
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.litote.kmongo.coroutine.CoroutineDatabase

fun Application.configureRouting(database: CoroutineDatabase, repositories: Repositories) {
    routing {
        post("/test") {
            call.respond(HttpStatusCode.OK, "Test successful")
        }

        post("/test111") {
            call.respond(HttpStatusCode.OK, "Test successful")
        }

        configureMongoDBStructureRouting(
            database = database
        )


        /**
         * # Basic Authentication
         * POST /auth/login                        // Email/password login
         * POST /auth/verify-login-code            // Submit login code
         * POST /auth/verify-login-token           // Submit login token
         * POST /auth/register                    // Email/password registration
         * POST /auth/verify-registration-code      // Submit registration code
         * POST /auth/verify-registration-token     // Submit registration token
         * POST /auth/complete-registration           // Submit registration token
         * POST /auth/request-password-reset        // Request password reset
         * POST /auth/reset-password              // Submit new password with token
         * POST /auth/logout                      // Logout (invalidate session)
         * GET  /auth/status                      // Check authentication status
         *
         * # OAuth
         * GET  /auth/oauth/google             // Initiate Google OAuth flow
         * GET  /auth/oauth/facebook           // Initiate Facebook OAuth flow
         * GET  /auth/oauth/apple              // Initiate Apple OAuth flow
         * GET  /auth/oauth/{provider}/callback // OAuth provider redirects here
         * POST /auth/link-oauth/{provider}    // Link OAuth account to existing account
         */





        configureAddCharacterRouting(
            charactersRepository = repositories.charactersRepository,
            authTokensForSessionRepository = repositories.authTokensForSessionRepository,
        )
        configureDeleteCharacterRouting(
            charactersRepository = repositories.charactersRepository,
            authTokensForSessionRepository = repositories.authTokensForSessionRepository,
        )
        configureGetCharactersRouting(
            charactersRepository = repositories.charactersRepository,
            reviewsRepository = repositories.reviewsRepository,
            usersRepository = repositories.usersRepository
        )

        configureAddReviewRouting(
            reviewsRepository = repositories.reviewsRepository,
            authTokensForSessionRepository = repositories.authTokensForSessionRepository,
        )
        configureDeleteReviewRouting(
            reviewsRepository = repositories.reviewsRepository,
            authTokensForSessionRepository = repositories.authTokensForSessionRepository,
        )
        configureGetReviewsRouting(
            reviewsRepository = repositories.reviewsRepository,
            usersRepository = repositories.usersRepository
        )
        configureUpdateReviewRouting(
            reviewsRepository = repositories.reviewsRepository,
            authTokensForSessionRepository = repositories.authTokensForSessionRepository,
        )

        configureGetUserByIdRouting(
            usersRepository = repositories.usersRepository,
        )
    }
}