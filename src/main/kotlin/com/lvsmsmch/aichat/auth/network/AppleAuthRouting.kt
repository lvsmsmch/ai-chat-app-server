package com.lvsmsmch.aichat.auth.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.UsernameGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.auth.AppleIdentityTokenVerifier
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.user.database.AccountType
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

/**
 * Вход через Apple. Клиент получает identity-токен у Apple и присылает его сюда;
 * сервер проверяет подпись и выпускает свою сессию — как с Google.
 *
 * Две особенности Apple, из-за которых флоу не копия гугловского:
 *  - почту Apple отдаёт ТОЛЬКО при первой авторизации приложения, дальше в
 *    токене остаётся один `sub`. Поэтому аккаунт ищется по `sub`, а почта
 *    записывается, только если пришла;
 *  - имя Apple вообще не кладёт в токен, оно приходит отдельным полем на
 *    клиенте и один раз — поэтому [AppleAuthRequest.fullName] опционален.
 *
 * Эндпоинты сами себя выключают, пока не задан APPLE_CLIENT_IDS: без него
 * проверить `aud` нечем, а принимать токен без этой проверки нельзя.
 */
fun Route.configureAppleAuthRouting(
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
    appleVerifier: AppleIdentityTokenVerifier,
    idGenerator: IdGenerator,
    usernameGenerator: UsernameGenerator,
    complexQueryHelper: ComplexQueryHelper,
    mapper: Mapper,
    discoverSectionsRepository: com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository,
) {
    fun requireConfigured() {
        if (!AppleIdentityTokenVerifier.isConfigured()) {
            throw AppleAuthNotConfiguredException()
        }
    }

    route("/auth") {
        rateLimit(RateLimitName("auth-strict")) {

            post("/apple") {
                requireConfigured()
                val request = call.receive<AppleAuthRequest>()
                validateDeviceId(request.deviceId)

                val apple = appleVerifier.verify(request.identityToken)

                val userDbo = userRepository.findByAppleId(apple.appleUserId)
                    // Тот же человек уже заходил по почте — подхватываем его
                    // аккаунт, а не создаём второй с тем же адресом
                    ?: apple.email?.let { userRepository.findUserByEmail(it) }?.also { existing ->
                        userRepository.linkAppleToUser(
                            userId = existing.id,
                            appleId = apple.appleUserId,
                            email = apple.email,
                            emailVerifiedByApple = apple.emailVerified,
                        )
                    }?.let { userRepository.getUserById(it.id) }
                    ?: UserDbo(
                        id = idGenerator.generateId(EntityType.USER),
                        username = usernameGenerator.generateUniqueUsername(),
                        appleOauthId = apple.appleUserId,
                        email = apple.email,
                        emailVerified = apple.email != null && apple.emailVerified,
                        name = request.fullName?.takeIf { it.isNotBlank() },
                        accountType = AccountType.REGISTERED,
                    ).also {
                        complexQueryHelper.addUser(it)
                        // Секции Discover — мгновенно из дефолтного набора
                        runCatching { discoverSectionsRepository.copyDefaultTo(it.id) }
                    }

                val sessionDbo = sessionRepository.createSession(userDbo.id, call.getUserIp())

                call.respondSuccess(
                    SuccessfulLoginDto(
                        userLoginInfoDto = userDbo.toUserLoginInfoDto(
                            mapper,
                            sessionToken = sessionDbo.token,
                        ),
                        userPrivateInfoDto = userDbo.toUserPrivateInfoDto(mapper),
                        userDto = userDbo.toUserDto(mapper),
                        userDetailsDto = userDbo.toUserDetailsDto(mapper, demanderId = userDbo.id),
                        limitsResponse = userRepository.getLimits(userDbo.id),
                    )
                )
            }

            /** Гость (или юзер с почтой) привязывает Apple к своему аккаунту. */
            post("/link-apple") {
                requireConfigured()
                val sessionDbo = sessionRepository.verifyToken(call)
                val request = call.receive<AppleConnectRequest>()

                userRepository.getUserById(sessionDbo.userId)
                    ?: throw BadRequestException("User does not exist")

                val apple = appleVerifier.verify(request.identityToken)

                val existing = userRepository.findByAppleId(apple.appleUserId)
                if (existing != null && existing.id != sessionDbo.userId) {
                    throw AppleAccountAlreadyInUseException()
                }
                // Чужая почта из токена не должна затирать адрес другого аккаунта
                val emailToSet = apple.email?.takeIf { email ->
                    val owner = userRepository.findUserByEmail(email)
                    owner == null || owner.id == sessionDbo.userId
                }

                userRepository.linkAppleToUser(
                    userId = sessionDbo.userId,
                    appleId = apple.appleUserId,
                    email = emailToSet,
                    emailVerifiedByApple = apple.emailVerified,
                )

                call.respondSuccess()
            }
        }
    }
}
