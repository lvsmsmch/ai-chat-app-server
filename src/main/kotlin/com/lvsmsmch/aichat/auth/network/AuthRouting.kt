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
                // Адрес берём только подтверждённый: по нему мы пускаем в чужой
                // аккаунт (см. ниже), и неподтверждённый тут — дыра
                val googleEmail = oauthUserData.email?.takeIf { oauthUserData.emailVerified }

                // Адрес свободен — новый аккаунт получит его сразу подтверждённым
                val freeEmail = googleEmail?.takeIf { userRepository.findUserByEmail(it) == null }

                val userDbo = userRepository.findByGoogleId(oauthUserData.id)
                // Этим адресом уже зарегистрирован аккаунт по почте: это тот же
                // человек нажал «Войти через Google» вместо пароля. Привязываем
                // Google к его аккаунту, иначе получилось бы два аккаунта с
                // одной почтой и вход по паролю попадал бы в случайный из них.
                //
                // Привязываемся только к ПОДТВЕРЖДЁННОЙ почте (или к аккаунту
                // без пароля): иначе кто угодно занял бы чужой адрес заранее и
                // получил доступ к аккаунту, в который войдёт настоящий хозяин.
                    ?: googleEmail?.let { email ->
                        userRepository.findUserByEmail(email)
                            ?.takeIf { it.googleOauthId == null }
                            ?.takeIf { it.emailVerified || it.hashedPassword == null }
                            ?.also {
                                userRepository.linkGoogleToUser(
                                    userId = it.id,
                                    googleId = oauthUserData.id,
                                    email = null, // адрес у аккаунта уже этот
                                )
                                if (!it.emailVerified) {
                                    userRepository.setEmailVerified(it.id, email)
                                }
                            }
                            ?.let { userRepository.getUserById(it.id) ?: it }
                    }
                    ?: UserDbo(
                        id = idGenerator.generateId(EntityType.USER),
                        username = usernameGenerator.generateUniqueUsername(),
                        googleOauthId = oauthUserData.id,
                        // Адрес занят другим аккаунтом, к которому привязаться
                        // нельзя — заводим аккаунт без почты, а не второй с той
                        // же: почта должна оставаться уникальной
                        email = freeEmail,
                        emailVerified = freeEmail != null,
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
         * Подъём сохранённой сессии при старте приложения.
         *
         * Отдаёт ровно то же, что вход: приложению нужны не только профиль, но
         * и флаги аккаунта (почта, пароль, Google) с лимитами — держать их в
         * локальном хранилище нельзя, они меняются с другого устройства.
         *
         * Заодно это проверка, что сессия жива: сервер гасит все сессии при
         * сбросе пароля, и клиент узнаёт об этом здесь.
         */
        get("/me") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val userDbo = userRepository.getUserById(sessionDbo.userId)
                ?: throw BadRequestException("User does not exist")

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
                if (!call.debugAccessAllowed()) {
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

                // Второй Google поверх первого — не «перепривязка», а потеря
                // доступа к аккаунту через прежний: отказываем явно
                if (userDbo.googleOauthId != null) {
                    throw BadRequestException("Account already has a linked Google account")
                }

                val oauthUserData = getOauthUserData(request.googleToken)

                val existingUser = userRepository.findByGoogleId(oauthUserData.id)
                if (existingUser != null) {
                    throw GoogleAccountAlreadyInUseException()
                }

                // Почту от Google пишем, только если у аккаунта её нет и адрес
                // не занят кем-то ещё: логин по почте должен оставаться
                // однозначным, а зарегистрированный адрес — не перетираться
                val googleEmail = oauthUserData.email
                    ?.takeIf { oauthUserData.emailVerified }
                    ?.takeIf { userDbo.email == null }
                    ?.takeIf { userRepository.findUserByEmail(it) == null }

                userRepository.linkGoogleToUser(
                    userId = sessionDbo.userId,
                    googleId = oauthUserData.id,
                    email = googleEmail,
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

        val rawEmail = json["email"]?.jsonPrimitive?.content
        OAuthUserData(
            id = json["sub"]?.jsonPrimitive?.content
                ?: throw Exception("Google response missing 'sub' field"),
            // Адрес приводим к нижнему регистру сразу: по нему ищут аккаунт, а
            // «A@x.com» и «a@x.com» — один и тот же ящик
            email = rawEmail?.let { com.lvsmsmch.aichat.auth.Emails.normalize(it) }
                ?.takeIf { it.isNotBlank() },
            emailVerified = json["email_verified"]?.jsonPrimitive?.content == "true",
            name = json["name"]?.jsonPrimitive?.content,
            profilePictureUrl = json["picture"]?.jsonPrimitive?.content
        )
    }
}
