package com.lvsmsmch.aichat.auth.network

import com.lvsmsmch.aichat._common.IdGenerator
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

fun Routing.configureAuthRouting(
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
    idGenerator: IdGenerator,
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

            // Получаем данные пользователя из Google
            val oauthUserData = HttpClient().use { client ->
                val apiUrl = "https://oauth2.googleapis.com/tokeninfo?id_token=${request.googleToken}"
                val response = client.get(apiUrl)
                if (response.status != HttpStatusCode.OK) {
                    application.log.error(
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
                    googleOauthId = oauthUserData.id,
                    email = oauthUserData.email,
                    name = oauthUserData.name,
                    profilePictureUrl = oauthUserData.profilePictureUrl,
                    accountType = AccountType.REGISTERED
                ).also { userRepository.addUser(it) }

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

            val userDbo = userRepository.findByDeviceId(request.deviceId)
                ?: UserDbo(
                    id = idGenerator.generateId(EntityType.USER),
                    deviceId = request.deviceId
                ).also { userRepository.addUser(it) }

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