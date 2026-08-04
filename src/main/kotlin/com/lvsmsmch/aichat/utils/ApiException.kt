package com.lvsmsmch.aichat.utils

import io.ktor.http.*

abstract class ApiException(
    val httpStatus: HttpStatusCode,
    val errorCode: String,
    val errorMessage: String = ""
) : RuntimeException(errorMessage)

class BadRequestException(
    errorMessage: String = "Bad Request"
) : ApiException(
    httpStatus = HttpStatusCode.BadRequest,
    errorCode = "bad_request",
    errorMessage = errorMessage
)

class OAuthException(
    errorMessage: String = "OAuth failed"
) : ApiException(
    httpStatus = HttpStatusCode.InternalServerError,
    errorCode = "oauth_failed",
    errorMessage = errorMessage
)

class TokenExpiredException(
    errorMessage: String = "Authentication token has expired"
) : ApiException(
    httpStatus = HttpStatusCode.Unauthorized,
    errorCode = "token_expired",
    errorMessage = errorMessage
)

class InvalidTokenException(
    errorMessage: String = "Authentication token is invalid"
) : ApiException(
    httpStatus = HttpStatusCode.Unauthorized,
    errorCode = "invalid_token",
    errorMessage = errorMessage
)

class NoUpdateFieldsProvidedException(
    errorMessage: String = "No update fields provided"
) : ApiException(
    httpStatus = HttpStatusCode.BadRequest,
    errorCode = "no_update_fields_provided",
    errorMessage = errorMessage
)

class ForbiddenException(
    errorMessage: String = "You don't have permission to do this action"
) : ApiException(
    httpStatus = HttpStatusCode.Forbidden,
    errorCode = "forbidden",
    errorMessage = errorMessage
)

class UsernameAlreadyTakenException(
    username: String? = null,
    errorMessage: String = "Username $username is already taken"
) : ApiException(
    httpStatus = HttpStatusCode.BadRequest,
    errorCode = "username_already_taken",
    errorMessage = errorMessage
)

class ValidationException(
    errorMessage: String = "Validation failed: "
) : ApiException(
    httpStatus = HttpStatusCode.BadRequest,
    errorCode = "validation_failed",
    errorMessage = errorMessage
)

class UserNotFoundException(
    id: String? = null,
    errorMessage: String = if (id != null) "User with id $id not found" else "User not found"
) : ApiException(
    httpStatus = HttpStatusCode.NotFound,
    errorCode = "user_not_found",
    errorMessage = errorMessage
)

class CharacterNotFoundException(
    id: String? = null,
    errorMessage: String = if (id != null) "Character with id $id not found" else "Character not found"
) : ApiException(
    httpStatus = HttpStatusCode.NotFound,
    errorCode = "character_not_found",
    errorMessage = errorMessage
)

class ChatNotFoundException(
    id: String? = null,
    errorMessage: String = if (id != null) "Chat with id $id not found" else "Chat not found"
) : ApiException(
    httpStatus = HttpStatusCode.NotFound,
    errorCode = "chat_not_found",
    errorMessage = errorMessage
)

class CommentNotFoundException(
    id: String? = null,
    errorMessage: String = if (id != null) "Comment with id $id not found" else "Comment not found"
) : ApiException(
    httpStatus = HttpStatusCode.NotFound,
    errorCode = "comment_not_found",
    errorMessage = errorMessage
)

class ReviewNotFoundException(
    id: String? = null,
    errorMessage: String = if (id != null) "Review with id $id not found" else "Review not found"
) : ApiException(
    httpStatus = HttpStatusCode.NotFound,
    errorCode = "review_not_found",
    errorMessage = errorMessage
)

class AlreadyReviewedException(
    errorMessage: String = "You already left a review for this character"
) : ApiException(
    httpStatus = HttpStatusCode.Conflict,
    errorCode = "already_reviewed",
    errorMessage = errorMessage
)

class GoogleAccountAlreadyInUseException(
    errorMessage: String = "This google account is already connected to another account."
) : ApiException(
    httpStatus = HttpStatusCode.Conflict,
    errorCode = "already_google_connected",
    errorMessage = errorMessage
)

class EmailAlreadyInUseException(
    errorMessage: String = "This email is already registered."
) : ApiException(
    httpStatus = HttpStatusCode.Conflict,
    errorCode = "email_already_in_use",
    errorMessage = errorMessage
)

/**
 * Единая ошибка на «нет такого адреса» и «неверный пароль». Разные сообщения
 * подсказали бы, какие адреса зарегистрированы.
 */
class InvalidCredentialsException(
    errorMessage: String = "Invalid email or password."
) : ApiException(
    httpStatus = HttpStatusCode.Unauthorized,
    errorCode = "invalid_credentials",
    errorMessage = errorMessage
)

/** Перебор пароля: аккаунт временно закрыт, в сообщении — сколько ждать. */
class AccountLockedException(
    val retryAfterSeconds: Long,
    errorMessage: String = "Too many attempts. Try again in $retryAfterSeconds seconds."
) : ApiException(
    httpStatus = HttpStatusCode.TooManyRequests,
    errorCode = "account_locked",
    errorMessage = errorMessage
)

/** Код из письма неверный, истёк или исчерпал попытки. */
class InvalidCodeException(
    errorCode: String = "invalid_code",
    errorMessage: String = "Invalid or expired code."
) : ApiException(
    httpStatus = HttpStatusCode.BadRequest,
    errorCode = errorCode,
    errorMessage = errorMessage
)

/** Повторную отправку письма просят слишком часто. */
class CodeRequestedTooSoonException(
    val retryAfterSeconds: Long,
    errorMessage: String = "Code already sent. Try again in $retryAfterSeconds seconds."
) : ApiException(
    httpStatus = HttpStatusCode.TooManyRequests,
    errorCode = "code_requested_too_soon",
    errorMessage = errorMessage
)

/** Вход по почте выключен на сервере (нет HTTPS — нет и пароля в открытую). */
class EmailAuthDisabledException(
    errorMessage: String = "Email sign-in is not available yet."
) : ApiException(
    httpStatus = HttpStatusCode.ServiceUnavailable,
    errorCode = "email_auth_disabled",
    errorMessage = errorMessage
)

class AppleAccountAlreadyInUseException(
    errorMessage: String = "This Apple account is already connected to another account."
) : ApiException(
    httpStatus = HttpStatusCode.Conflict,
    errorCode = "already_apple_connected",
    errorMessage = errorMessage
)

/** APPLE_CLIENT_IDS не задан — проверять aud токена нечем, вход запрещён. */
class AppleAuthNotConfiguredException(
    errorMessage: String = "Apple sign-in is not available yet."
) : ApiException(
    httpStatus = HttpStatusCode.ServiceUnavailable,
    errorCode = "apple_auth_not_configured",
    errorMessage = errorMessage
)

class InternalServerErrorException(
    errorMessage: String = "An unexpected error occurred"
) : ApiException(
    httpStatus = HttpStatusCode.InternalServerError,
    errorCode = "unknown",
    errorMessage = errorMessage
)