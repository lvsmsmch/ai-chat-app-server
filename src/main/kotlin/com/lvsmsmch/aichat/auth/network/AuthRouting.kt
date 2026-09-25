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
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.logging.Log

fun Route.configureAuthRouting(
    userRepository: UserRepository,
    deviceLimitCarryoverRepository: com.lvsmsmch.aichat.user.database.DeviceLimitCarryoverRepository,
    sessionRepository: SessionRepository,
    idGenerator: IdGenerator,
    usernameGenerator: UsernameGenerator,
    mapper: Mapper,
    discoverSectionsRepository: com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository,
    userService: com.lvsmsmch.aichat.user.UserService,
    subscriptionRepository: com.lvsmsmch.aichat.billing.SubscriptionRepository,
    purchaseVerifier: com.lvsmsmch.aichat.billing.PlayPurchaseVerifier,
) {
    route("/auth") {

        rateLimit(RateLimitName("auth-strict")) {
            post("/google") {
                val request = call.receive<GoogleAuthRequest>()

                validateDeviceId(request.deviceId)

                val oauthUserData = getOauthUserData(request.googleToken)

                val userDbo = userRepository.findByGoogleId(oauthUserData.id)
                    ?: UserDbo(
                        id = idGenerator.generateId(EntityType.USER),
                        username = usernameGenerator.generateUniqueUsername(),
                        googleOauthId = oauthUserData.id,
                        email = oauthUserData.email,
                        name = oauthUserData.name,
                        profilePictureUrl = null,
                        accountType = AccountType.REGISTERED
                    ).also {
                        userService.addUser(it)
                        // Секции Discover — мгновенно из дефолтного набора
                        runCatching { discoverSectionsRepository.copyDefaultTo(it.id) }
                    }

                val sessionDbo = sessionRepository.createSession(userDbo.id, call.getUserIp())

                call.respondSuccess(
                    SuccessfulLoginDto(
                        userLoginInfoDto = userDbo.toUserLoginInfoDto(mapper, sessionToken = sessionDbo.token),
                        userPrivateInfoDto = userDbo.toUserPrivateInfoDto(mapper),
                        userDto = userDbo.toUserDto(mapper),
                        userDetailsDto = userDbo.toUserDetailsDto(mapper, demanderId = userDbo.id),
                        limitsResponse = userRepository.getLimits(userDbo.id),
                    )
                )
            }

            post("/guest") {
                val request = call.receive<GuestAuthRequest>()

                validateDeviceId(request.deviceId)

                val userDbo =
                    null
                    ?: userRepository.findByDeviceId(request.deviceId)
                    ?: run {
                        // Анти-абьюз: на этом устройстве недавно удаляли аккаунт —
                        // потраченные лимиты наследуются новым гостем
                        val carry = runCatching {
                            deviceLimitCarryoverRepository.take(request.deviceId)
                        }.getOrNull()
                        UserDbo(
                            id = idGenerator.generateId(EntityType.USER),
                            username = usernameGenerator.generateUniqueUsername(),
                            deviceId = request.deviceId,
                            hourlyMessageCount = carry?.hourlyMessageCount ?: 0,
                            dailyMessageCount = carry?.dailyMessageCount ?: 0,
                            monthlyMessageCount = carry?.monthlyMessageCount ?: 0,
                            monthlyTopModelCount = carry?.monthlyTopModelCount ?: 0,
                            dailyImageCount = carry?.dailyImageCount ?: 0,
                            monthlyTopImageCount = carry?.monthlyTopImageCount ?: 0,
                        ).also {
                            userService.addUser(it)
                            // Секции Discover — мгновенно из дефолтного набора
                            runCatching { discoverSectionsRepository.copyDefaultTo(it.id) }
                        }
                    }

                val sessionDbo = sessionRepository.createSession(userDbo.id, call.getUserIp())

                call.respondSuccess(
                    SuccessfulLoginDto(
                        userLoginInfoDto = userDbo.toUserLoginInfoDto(mapper, sessionToken = sessionDbo.token),
                        userPrivateInfoDto = userDbo.toUserPrivateInfoDto(mapper),
                        userDto = userDbo.toUserDto(mapper),
                        userDetailsDto = userDbo.toUserDetailsDto(mapper, demanderId = userDbo.id),
                        limitsResponse = userRepository.getLimits(userDbo.id),
                    )
                )
            }
        }


        /**
         * Статус подписки.
         *
         * С токеном покупки — боевой путь: подписку подтверждает Google, а не
         * приложение. Без токена флаг переключается напрямую, и это разрешено
         * только в отладочном режиме: иначе премиум включался бы одним
         * запросом с любого устройства.
         */
        post("/subscription") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val request = call.receive<SubscriptionStatusRequest>()

            val userDbo = userRepository.getUserById(sessionDbo.userId)
                ?: throw BadRequestException("User does not exist")

            val purchaseToken = request.purchaseToken?.takeIf { it.isNotBlank() }
            if (purchaseToken == null) {
                if (System.getenv("DEBUG_ENDPOINTS")?.toBoolean() != true) {
                    throw ForbiddenException(errorMessage = "Purchase token is required")
                }
                userRepository.updateSubscriptionStatus(userDbo.id, request.hasSubscription)
                if (!request.hasSubscription) subscriptionRepository.clear(userDbo.id)
                call.respondSuccess()
                return@post
            }

            val active = when (val check = purchaseVerifier.verify(purchaseToken)) {
                com.lvsmsmch.aichat.billing.PurchaseCheck.Active -> true
                com.lvsmsmch.aichat.billing.PurchaseCheck.Inactive -> false
                // Ключа для проверки ещё нет: сам факт токена означает, что
                // покупка через Play была, но подтвердить её мы не можем —
                // верим и громко пишем об этом в лог
                com.lvsmsmch.aichat.billing.PurchaseCheck.NotConfigured -> {
                    logger.warn("Purchase accepted without verification (no Play service account)")
                    true
                }
                is com.lvsmsmch.aichat.billing.PurchaseCheck.Error ->
                    throw BadRequestException("Purchase verification failed: ${check.message}")
            }

            subscriptionRepository.save(
                userId = userDbo.id,
                purchaseToken = purchaseToken,
                productId = request.productId ?: "premium",
                active = active,
            )
            userRepository.updateSubscriptionStatus(userDbo.id, active)

            call.respondSuccess()
        }

        rateLimit(RateLimitName("auth-strict")) {
            post("/link-google") {
                val sessionDbo = sessionRepository.verifyToken(call)
                val request = call.receive<GoogleConnectRequest>()

                val userDbo = userRepository.getUserById(sessionDbo.userId)
                    ?: throw BadRequestException("User does not exist")

                val oauthUserData = getOauthUserData(request.googleToken)

                val existingUser = userRepository.findByGoogleId(oauthUserData.id)
                if (existingUser != null) {
                    throw GoogleAccountAlreadyInUseException()
                }

                userRepository.linkGoogleToUser(
                    userId = sessionDbo.userId,
                    googleId = oauthUserData.id,
                    email = oauthUserData.email,
                )

                call.respondSuccess()
            }
        }


        post("/logout") {
            val sessionDbo = sessionRepository.verifyToken(call)
            sessionRepository.delete(sessionDbo.token)
            call.respondSuccess()
        }
    }
}


/**
 * Один клиент на процесс. Раньше на КАЖДЫЙ вход через Google создавался новый
 * HttpClient — это свой пул соединений и потоки на один запрос.
 */
private val googleClient by lazy { HttpClient() }

private suspend fun getOauthUserData(googleToken: String): OAuthUserData {
    return googleClient.let { client ->
        val googleOauthTokenInfoUrl = System.getenv("GOOGLE_OAUTH_TOKEN_INFO_URL")
            ?: throw Exception("Missing GOOGLE_OAUTH_TOKEN_INFO_URL key")
        val apiUrl = "$googleOauthTokenInfoUrl?id_token=${googleToken}"
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

        // Reject tokens issued to other applications. Enforced only when the
        // expected client id is configured, so the variable stays optional.
        val expectedClientId = System.getenv("GOOGLE_OAUTH_CLIENT_ID")
        if (!expectedClientId.isNullOrBlank()) {
            val audience = json["aud"]?.jsonPrimitive?.content
            if (audience != expectedClientId) {
                logger.error("OAuth token audience mismatch: $audience")
                throw OAuthException()
            }
        }

        OAuthUserData(
            id = json["sub"]?.jsonPrimitive?.content
                ?: throw Exception("Google response missing 'sub' field"),
            email = json["email"]?.jsonPrimitive?.content,
            name = json["name"]?.jsonPrimitive?.content,
            profilePictureUrl = json["picture"]?.jsonPrimitive?.content
        )
    }
}
