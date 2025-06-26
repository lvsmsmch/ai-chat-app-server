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
import io.ktor.server.plugins.*
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

        post("/google") {
            val deviceId = call.request.queryParameters["deviceId"]
                ?: throw BadRequestException("Missing deviceId parameter")
            
            val googleToken = call.request.queryParameters["googleToken"]
                ?: throw BadRequestException("Missing googleToken parameter")

            validateDeviceId(deviceId)

            // Получаем данные пользователя из Google
            val oauthUserData = HttpClient().use { client ->
                val apiUrl = "https://oauth2.googleapis.com/tokeninfo?id_token=${googleToken}"
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
                ?: userRepository.findByDeviceId(deviceId)?.let {
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
                    userDetailsDto = userDbo.toUserDetailsDto(mapper, isDemanderFollowingThisUser = false),
                )
            )
        }

        post("/guest") {
            val deviceId = call.request.queryParameters["deviceId"]
                ?: throw BadRequestException("Missing deviceId parameter")

            validateDeviceId(deviceId)

            val userDbo = userRepository.findByDeviceId(deviceId)
                ?: UserDbo(
                    id = idGenerator.generateId(EntityType.USER),
                    deviceId = deviceId
                ).also { userRepository.addUser(it) }

            val sessionDbo = sessionRepository.createSession(userDbo.id, call.getUserIp())

            call.respondSuccess(
                SuccessfulLoginDto(
                    userLoginInfoDto = userDbo.toUserLoginInfoDto(mapper, sessionToken = sessionDbo.token),
                    userPrivateInfoDto = userDbo.toUserPrivateInfoDto(mapper),
                    userDto = userDbo.toUserDto(mapper),
                    userDetailsDto = userDbo.toUserDetailsDto(mapper, isDemanderFollowingThisUser = false),
                )
            )
        }

        post("/logout") {
            val sessionDbo = sessionRepository.verifyToken(call)
            sessionRepository.delete(sessionDbo.token)
            call.respondSuccess()
        }
    }
}