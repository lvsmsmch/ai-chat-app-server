package com.lvsmsmch.aichat.auth.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.UsernameGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.user.database.AccountType
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun Route.configureAuthRouting(
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
    idGenerator: IdGenerator,
    usernameGenerator: UsernameGenerator,
    complexQueryHelper: ComplexQueryHelper,
    mapper: Mapper
) {
    route("/auth") {

        /**
         * POST /auth/google
         * Авторизация через Google OAuth
         */
        post("/google") {
            val request = call.receive<GoogleAuthRequest>()

            validateDeviceId(request.deviceId)

            val oauthUserData = HttpClient().use { client ->
                val googleOauthTokenInfoUrl = System.getenv("GOOGLE_OAUTH_TOKEN_INFO_URL")
                    ?: throw Exception("Missing GOOGLE_OAUTH_TOKEN_INFO_URL key")
                val apiUrl = "$googleOauthTokenInfoUrl?id_token=${request.googleToken}"
                val response = client.get(apiUrl)
                if (response.status != HttpStatusCode.OK) {
                    logger.error(
                        "OAuth response error occurred, " +
                                "status: ${response.status}, " +
                                "body: ${response.bodyAsText()}"
                    )
                    throw OAuthException()
                }
                val responseBody = response.bodyAsText()
                val json = Json.parseToJsonElement(responseBody).jsonObject
                OAuthUserData(
                    id = json["sub"]?.jsonPrimitive?.content
                        ?: throw Exception("Google response missing 'sub' field"),
                    email = json["email"]?.jsonPrimitive?.content,
                    name = json["name"]?.jsonPrimitive?.content,
                    profilePictureUrl = json["picture"]?.jsonPrimitive?.content
                )
            }

            val userDbo = userRepository.findByGoogleId(oauthUserData.id)
                ?: userRepository.findByDeviceId(request.deviceId)?.let {
                    userRepository.linkGoogleToUser(
                        userId = it.id,
                        googleId = oauthUserData.id,
                        email = oauthUserData.email,
                        name = oauthUserData.name,
                        profilePictureUrl = oauthUserData.profilePictureUrl,
                    )
                    userRepository.getUserById(it.id)!!
                } ?: UserDbo(
                    id = idGenerator.generateId(EntityType.USER),
                    username = usernameGenerator.generateUniqueUsername(),
                    googleOauthId = oauthUserData.id,
                    email = oauthUserData.email,
                    name = oauthUserData.name,
                    profilePictureUrl = oauthUserData.profilePictureUrl,
                    accountType = AccountType.REGISTERED
                ).also {
                    complexQueryHelper.addUser(it)
                }

            val sessionDbo = sessionRepository.createSession(userDbo.id, call.getUserIp())

            call.respondSuccess(
                SuccessfulLoginDto(
                    userLoginInfoDto = userDbo.toUserLoginInfoDto(mapper, sessionToken = sessionDbo.token),
                    userPrivateInfoDto = userDbo.toUserPrivateInfoDto(mapper),
                    userDto = userDbo.toUserDto(mapper),
                    userDetailsDto = userDbo.toUserDetailsDto(mapper, demanderId = userDbo.id),
                )
            )
        }

        /**
         * POST /auth/guest
         * Авторизация как гость
         */
        post("/guest") {
            val request = call.receive<GuestAuthRequest>()

            validateDeviceId(request.deviceId)

            val userDbo = userRepository.findByUsername("lvsm")
                ?: userRepository.findByDeviceId(request.deviceId)
                ?: UserDbo(
                    id = idGenerator.generateId(EntityType.USER),
                    username = usernameGenerator.generateUniqueUsername(),
                    deviceId = request.deviceId
                ).also {
                    complexQueryHelper.addUser(it)
                }

            val sessionDbo = sessionRepository.createSession(userDbo.id, call.getUserIp())

            call.respondSuccess(
                SuccessfulLoginDto(
                    userLoginInfoDto = userDbo.toUserLoginInfoDto(mapper, sessionToken = sessionDbo.token),
                    userPrivateInfoDto = userDbo.toUserPrivateInfoDto(mapper),
                    userDto = userDbo.toUserDto(mapper),
                    userDetailsDto = userDbo.toUserDetailsDto(mapper, demanderId = userDbo.id),
                )
            )
        }


        /**
         * POST /auth/subscription
         * Авторизация как гость
         */
        post("/subscription") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val request = call.receive<SubscriptionStatusRequest>()

            val userDbo = userRepository.getUserById(sessionDbo.userId)
                ?: throw BadRequestException("User does not exist")

            userRepository.updateSubscriptionStatus(userDbo.id, request.hasSubscription)

            call.respondSuccess()
        }


        /**
         * POST /auth/logout
         * Выход из системы
         */
        post("/logout") {
            val sessionDbo = sessionRepository.verifyToken(call)
            sessionRepository.delete(sessionDbo.token)
            call.respondSuccess()
        }
    }
}