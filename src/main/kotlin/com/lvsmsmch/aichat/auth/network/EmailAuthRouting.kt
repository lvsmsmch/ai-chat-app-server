package com.lvsmsmch.aichat.auth.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.UsernameGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.auth.Emails
import com.lvsmsmch.aichat.auth.Passwords
import com.lvsmsmch.aichat.auth.database.AuthCodePurpose
import com.lvsmsmch.aichat.auth.database.AuthCodeRepository
import com.lvsmsmch.aichat.auth.database.AuthLockoutRepository
import com.lvsmsmch.aichat.auth.database.CodeCheck
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.mail.MailSender
import com.lvsmsmch.aichat.mail.MailTemplates
import com.lvsmsmch.aichat.user.database.AccountType
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

/**
 * Вход по почте и паролю: регистрация, вход, подтверждение адреса, сброс и
 * смена пароля, привязка почты к гостевому аккаунту.
 *
 * Подтверждение и сброс сделаны КОДОМ из письма, а не ссылкой: код вводится в
 * приложении, поэтому не нужен ни сайт, ни домен, и юзер не уходит из аппа.
 *
 * Три уровня защиты от перебора:
 *  1. лимит запросов по IP — rate-limit `auth-strict` на всей группе;
 *  2. блокировка по аккаунту с растущей паузой ([AuthLockoutRepository]) —
 *     лимит по IP не спасает от распределённой атаки;
 *  3. у кода из письма короткий срок, пять попыток и одноразовость.
 *
 * Ответы намеренно не различают «нет такого адреса» и «неверный пароль», а
 * запрос сброса всегда отвечает успехом — иначе эндпоинт превращается в
 * проверялку «зарегистрирован ли этот адрес».
 */
fun Route.configureEmailAuthRouting(
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
    authCodeRepository: AuthCodeRepository,
    authLockoutRepository: AuthLockoutRepository,
    mailSender: MailSender,
    idGenerator: IdGenerator,
    usernameGenerator: UsernameGenerator,
    mapper: Mapper,
    discoverSectionsRepository: com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository,
    userService: com.lvsmsmch.aichat.user.UserService,
) {
    /**
     * Пароль в открытом виде по HTTP читается кем угодно на пути, поэтому вход
     * по почте включается только вместе с HTTPS — переменной EMAIL_AUTH_ENABLED.
     */
    fun requireEnabled() {
        val enabled = System.getenv("EMAIL_AUTH_ENABLED")?.toBoolean() ?: false
        if (!enabled) throw EmailAuthDisabledException()
    }

    suspend fun sendCode(user: UserDbo, purpose: AuthCodePurpose, email: String) {
        val lastIssuedAt = authCodeRepository.lastIssuedAt(user.id, purpose)
        if (lastIssuedAt != null) {
            val since = runCatching { UtcTimestamp.parse(lastIssuedAt) }.getOrNull()
            if (since != null) {
                val elapsed = java.time.Duration
                    .between(since.instant, java.time.Instant.now())
                    .seconds
                val wait = AuthCodeRepository.RESEND_COOLDOWN_SECONDS - elapsed
                if (wait > 0) throw CodeRequestedTooSoonException(retryAfterSeconds = wait)
            }
        }
        val code = authCodeRepository.issue(user.id, purpose, email)
        val (subject, text) = when (purpose) {
            AuthCodePurpose.EMAIL_VERIFY ->
                MailTemplates.verifyEmail(code, AuthCodeRepository.TTL_MINUTES)
            AuthCodePurpose.PASSWORD_RESET ->
                MailTemplates.resetPassword(code, AuthCodeRepository.TTL_MINUTES)
        }
        mailSender.send(email, subject, text)
    }

    /** Ответ входа/регистрации — тот же, что у Google и гостя. */
    suspend fun ApplicationCall.respondLogin(user: UserDbo) {
        val session = sessionRepository.createSession(user.id, getUserIp())
        respondSuccess(
            SuccessfulLoginDto(
                userLoginInfoDto = user.toUserLoginInfoDto(mapper, sessionToken = session.token),
                userPrivateInfoDto = user.toUserPrivateInfoDto(mapper),
                userDto = user.toUserDto(mapper),
                userDetailsDto = user.toUserDetailsDto(mapper, demanderId = user.id),
                limitsResponse = userRepository.getLimits(user.id),
            )
        )
    }

    route("/auth/email") {
        rateLimit(RateLimitName("auth-strict")) {

            /**
             * Регистрация. Аккаунт создаётся сразу и сессия выдаётся сразу, но
             * с `emailVerified = false` — клиент по этому флагу показывает экран
             * ввода кода. Иначе юзер, закрывший приложение до подтверждения,
             * остался бы с аккаунтом, в который нельзя войти.
             */
            post("/register") {
                requireEnabled()
                val request = call.receive<EmailRegisterRequest>()
                validateDeviceId(request.deviceId)
                val email = Emails.validateAndNormalize(request.email)
                Passwords.validate(request.password, email)

                if (userRepository.findUserByEmail(email) != null) {
                    throw EmailAlreadyInUseException()
                }

                val user = UserDbo(
                    id = idGenerator.generateId(EntityType.USER),
                    username = usernameGenerator.generateUniqueUsername(),
                    email = email,
                    emailVerified = false,
                    hashedPassword = Passwords.hash(request.password),
                    accountType = AccountType.REGISTERED,
                ).also {
                    userService.addUser(it)
                    // Секции Discover — мгновенно из дефолтного набора
                    runCatching { discoverSectionsRepository.copyDefaultTo(it.id) }
                }

                runCatching { sendCode(user, AuthCodePurpose.EMAIL_VERIFY, email) }
                call.respondLogin(user)
            }

            post("/login") {
                requireEnabled()
                val request = call.receive<EmailLoginRequest>()
                validateDeviceId(request.deviceId)
                val email = Emails.validateAndNormalize(request.email)

                val locked = authLockoutRepository.secondsRemaining(email)
                if (locked > 0) throw AccountLockedException(retryAfterSeconds = locked)

                val user = userRepository.findUserByEmail(email)
                // Пароль проверяем даже когда юзера нет: одинаковое время
                // ответа не выдаёт, зарегистрирован ли адрес
                val matches = Passwords.matches(request.password, user?.hashedPassword)
                if (user == null || !matches) {
                    authLockoutRepository.registerFailure(email)
                    throw InvalidCredentialsException()
                }
                authLockoutRepository.clear(email)
                call.respondLogin(user)
            }

            /** Повторная отправка кода подтверждения (кулдаун — минута). */
            post("/send-code") {
                requireEnabled()
                val sessionDbo = sessionRepository.verifyToken(call)
                val user = userRepository.getUserById(sessionDbo.userId)
                    ?: throw UserNotFoundException()
                val email = user.email ?: throw BadRequestException("Account has no email")
                if (user.emailVerified) {
                    call.respondSuccess()
                    return@post
                }
                sendCode(user, AuthCodePurpose.EMAIL_VERIFY, email)
                call.respondSuccess()
            }

            post("/verify") {
                requireEnabled()
                val sessionDbo = sessionRepository.verifyToken(call)
                val request = call.receive<EmailVerifyRequest>()
                val user = userRepository.getUserById(sessionDbo.userId)
                    ?: throw UserNotFoundException()

                when (val check = authCodeRepository.checkAndConsume(
                    userId = user.id,
                    purpose = AuthCodePurpose.EMAIL_VERIFY,
                    code = request.code,
                )) {
                    is CodeCheck.Ok -> {
                        // Код мог быть выдан на новый адрес (смена почты) —
                        // подтверждаем именно тот, на который он ушёл
                        userRepository.setEmailVerified(user.id, check.code.email)
                        call.respondSuccess()
                    }
                    CodeCheck.Wrong -> throw InvalidCodeException()
                    CodeCheck.NotFound -> throw InvalidCodeException(
                        errorMessage = "Code expired or not requested. Request a new one."
                    )
                    CodeCheck.TooManyAttempts -> throw InvalidCodeException(
                        errorCode = "too_many_code_attempts",
                        errorMessage = "Too many wrong attempts. Request a new code.",
                    )
                }
            }

            /**
             * Забыли пароль. Всегда отвечает успехом, даже если адреса нет:
             * иначе по ответу можно собирать список зарегистрированных почт.
             */
            post("/forgot-password") {
                requireEnabled()
                val request = call.receive<ForgotPasswordRequest>()
                val email = Emails.validateAndNormalize(request.email)
                val user = userRepository.findUserByEmail(email)
                if (user != null) {
                    // Кулдаун глотаем: он не должен раскрывать существование адреса
                    runCatching { sendCode(user, AuthCodePurpose.PASSWORD_RESET, email) }
                }
                call.respondSuccess()
            }

            /**
             * Сброс пароля по коду. Заодно подтверждает адрес (юзер доказал, что
             * читает эту почту) и гасит ВСЕ сессии: если в аккаунт кто-то залез,
             * смена пароля должна его выкинуть.
             */
            post("/reset-password") {
                requireEnabled()
                val request = call.receive<ResetPasswordRequest>()
                val email = Emails.validateAndNormalize(request.email)
                Passwords.validate(request.newPassword, email)

                val user = userRepository.findUserByEmail(email)
                    ?: throw InvalidCodeException()

                when (val check = authCodeRepository.checkAndConsume(
                    userId = user.id,
                    purpose = AuthCodePurpose.PASSWORD_RESET,
                    code = request.code,
                )) {
                    is CodeCheck.Ok -> {
                        userRepository.setPassword(user.id, Passwords.hash(request.newPassword))
                        userRepository.setEmailVerified(user.id, check.code.email)
                        sessionRepository.deleteAllByUserId(user.id)
                        authLockoutRepository.clear(email)
                        call.respondSuccess()
                    }
                    CodeCheck.Wrong -> throw InvalidCodeException()
                    CodeCheck.NotFound -> throw InvalidCodeException(
                        errorMessage = "Code expired or not requested. Request a new one."
                    )
                    CodeCheck.TooManyAttempts -> throw InvalidCodeException(
                        errorCode = "too_many_code_attempts",
                        errorMessage = "Too many wrong attempts. Request a new code.",
                    )
                }
            }

            /** Смена пароля изнутри аккаунта: нужен текущий пароль. */
            post("/change-password") {
                requireEnabled()
                val sessionDbo = sessionRepository.verifyToken(call)
                val request = call.receive<ChangePasswordRequest>()
                val user = userRepository.getUserById(sessionDbo.userId)
                    ?: throw UserNotFoundException()

                if (!Passwords.matches(request.currentPassword, user.hashedPassword)) {
                    throw InvalidCredentialsException("Current password is incorrect.")
                }
                Passwords.validate(request.newPassword, user.email)
                userRepository.setPassword(user.id, Passwords.hash(request.newPassword))
                // Остальные устройства разлогиниваются, текущее — остаётся
                sessionRepository.deleteAllByUserIdExcept(user.id, sessionDbo.token)
                call.respondSuccess()
            }

            /**
             * Гость заводит себе почту и пароль, не теряя аккаунт: чаты,
             * персонажи и лимиты остаются те же.
             */
            post("/link") {
                requireEnabled()
                val sessionDbo = sessionRepository.verifyToken(call)
                val request = call.receive<LinkEmailRequest>()
                val email = Emails.validateAndNormalize(request.email)
                Passwords.validate(request.password, email)

                val user = userRepository.getUserById(sessionDbo.userId)
                    ?: throw UserNotFoundException()
                if (user.email != null) {
                    throw BadRequestException("Account already has an email")
                }
                if (userRepository.findUserByEmail(email) != null) {
                    throw EmailAlreadyInUseException()
                }

                userRepository.attachEmailPassword(
                    userId = user.id,
                    email = email,
                    hashedPassword = Passwords.hash(request.password),
                )
                val updated = userRepository.getUserById(user.id) ?: throw UserNotFoundException()
                runCatching { sendCode(updated, AuthCodePurpose.EMAIL_VERIFY, email) }
                call.respondSuccess()
            }
        }
    }
}
