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
    mapper: Mapper,
    discoverSectionsRepository: com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository,
    userService: com.lvsmsmch.aichat.user.UserService,
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

                // Адрес принимаем только подтверждённый: по нему мы пускаем в
                // существующий аккаунт, и неподтверждённый тут — дыра
                val appleEmail = apple.email?.takeIf { apple.emailVerified }
                // Свободен ли адрес — от этого зависит, можно ли его записать
                val freeEmail = appleEmail
                    ?.takeIf { userRepository.findUserByEmail(it) == null }

                val userDbo = userRepository.findByAppleId(apple.appleUserId)
                    // Тот же человек уже заходил по почте — подхватываем его
                    // аккаунт, а не создаём второй с тем же адресом.
                    //
                    // Только если адрес у того аккаунта ПОДТВЕРЖДЁН (или пароля
                    // у него нет): иначе чужой адрес занимали бы заранее и
                    // получали доступ к аккаунту настоящего владельца
                    ?: appleEmail?.let { email ->
                        userRepository.findUserByEmail(email)
                            ?.takeIf { it.appleOauthId == null }
                            ?.takeIf { it.emailVerified || it.hashedPassword == null }
                            ?.also { existing ->
                                userRepository.linkAppleToUser(
                                    userId = existing.id,
                                    appleId = apple.appleUserId,
                                    email = email,
                                    emailVerifiedByApple = apple.emailVerified,
                                )
                            }?.let { userRepository.getUserById(it.id) ?: it }
                    }
                    ?: UserDbo(
                        id = idGenerator.generateId(EntityType.USER),
                        username = usernameGenerator.generateUniqueUsername(),
                        appleOauthId = apple.appleUserId,
                        // Адрес занят аккаунтом, к которому привязаться нельзя —
                        // заводим без почты: она должна остаться уникальной
                        email = freeEmail,
                        emailVerified = freeEmail != null,
                        name = request.fullName?.takeIf { it.isNotBlank() },
                        accountType = AccountType.REGISTERED,
                    ).also {
                        userService.addUser(it)
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

                val userDbo = userRepository.getUserById(sessionDbo.userId)
                    ?: throw BadRequestException("User does not exist")

                // Второй Apple поверх первого — не «перепривязка», а потеря
                // доступа через прежний: отказываем явно
                if (userDbo.appleOauthId != null) {
                    throw BadRequestException("Account already has a linked Apple account")
                }

                val apple = appleVerifier.verify(request.identityToken)

                val existing = userRepository.findByAppleId(apple.appleUserId)
                if (existing != null && existing.id != sessionDbo.userId) {
                    throw AppleAccountAlreadyInUseException()
                }
                // Почту пишем, только если своей нет и адрес не занят: чужой
                // адрес не должен затирать логин другого аккаунта
                val emailToSet = apple.email
                    ?.takeIf { apple.emailVerified }
                    ?.takeIf { userDbo.email == null }
                    ?.takeIf { email ->
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
