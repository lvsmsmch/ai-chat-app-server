package com.lvsmsmch.aichat.utils

import io.ktor.http.*

abstract class ApiException(
    val httpStatus: HttpStatusCode,
    val errorCode: String,
    val errorMessage: String = ""
) : RuntimeException(errorMessage)

// Authentication errors
class EmailAlreadyInUseException(
    errorMessage: String = "Email is already in use"
) : ApiException(
    httpStatus = HttpStatusCode.Conflict,
    errorCode = "email_already_in_use",
    errorMessage = errorMessage
)

class UserWithThisEmailNotFoundException(
    errorMessage: String = "User with this email not found"
) : ApiException(
    httpStatus = HttpStatusCode.NotFound,
    errorCode = "user_with_this_email_not_found",
    errorMessage = errorMessage
)

class OAuthException(
    errorMessage: String = "OAuth failed"
) : ApiException(
    httpStatus = HttpStatusCode.InternalServerError,
    errorCode = "oauth_failed",
    errorMessage = errorMessage
)

class WrongPasswordException(
    errorMessage: String = "Incorrect password"
) : ApiException(
    httpStatus = HttpStatusCode.Unauthorized,
    errorCode = "wrong_password",
    errorMessage = errorMessage
)

class WrongCodeException(
    errorMessage: String = "Verification code is invalid"
) : ApiException(
    httpStatus = HttpStatusCode.Unauthorized,
    errorCode = "wrong_code",
    errorMessage = errorMessage
)

class TokenExpiredException(
    errorMessage: String = "Authentication token has expired"
) : ApiException(
    httpStatus = HttpStatusCode.Unauthorized,
    errorCode = "token_expired",
    errorMessage = errorMessage
)

class RequiresPremiumException(
    errorMessage: String = "This feature requires premium subscription"
) : ApiException(
    httpStatus = HttpStatusCode.Forbidden,
    errorCode = "requires_premium",
    errorMessage = errorMessage
)

class NoUpdateFieldsProvidedException(
    errorMessage: String = "No update fields provided"
) : ApiException(
    httpStatus = HttpStatusCode.BadRequest,
    errorCode = "no_update_fields_provided",
    errorMessage = errorMessage
)

// Rate limiting errors
class TooManyAttemptsException(
    errorMessage: String = "Too many attempts, please try again later"
) : ApiException(
    httpStatus = HttpStatusCode.TooManyRequests,
    errorCode = "too_many_attempts",
    errorMessage = errorMessage
)

class CantSendCodeTooManyAttemptsException(
    errorMessage: String = "Cannot send new code, too many attempts"
) : ApiException(
    httpStatus = HttpStatusCode.TooManyRequests,
    errorCode = "cant_send_code_too_many_attempts",
    errorMessage = errorMessage
)

class CantSendTokenTooManyAttemptsException(
    errorMessage: String = "Cannot send new token, too many attempts"
) : ApiException(
    httpStatus = HttpStatusCode.TooManyRequests,
    errorCode = "cant_send_token_too_many_attempts",
    errorMessage = errorMessage
)

class ForbiddenException(
    errorMessage: String = "You don't have permission to do this action"
) : ApiException(
    httpStatus = HttpStatusCode.Forbidden,
    errorCode = "forbidden",
    errorMessage = errorMessage
)

// Validation errors
class ValidationException(
    errorMessage: String = "Validation failed: "
) : ApiException(
    httpStatus = HttpStatusCode.BadRequest,
    errorCode = "validation_failed",
    errorMessage = errorMessage
)

// User-specific errors
class UserNotFoundException(
    id: String? = null,
    errorMessage: String = if (id != null) "User with id $id not found" else "User not found"
) : ApiException(
    httpStatus = HttpStatusCode.NotFound,
    errorCode = "character_not_found",
    errorMessage = errorMessage
)

// Character-specific errors
class CharacterNotFoundException(
    id: String? = null,
    errorMessage: String = if (id != null) "Character with id $id not found" else "Character not found"
) : ApiException(
    httpStatus = HttpStatusCode.NotFound,
    errorCode = "character_not_found",
    errorMessage = errorMessage
)

// Chat-specific errors
class ChatNotFoundException(
    id: String? = null,
    errorMessage: String = if (id != null) "Chat with id $id not found" else "Chat not found"
) : ApiException(
    httpStatus = HttpStatusCode.NotFound,
    errorCode = "chat_not_found",
    errorMessage = errorMessage
)

// Review-specific errors
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

class CouldNotVerifyPurchase(
    errorMessage: String = "Could not verify purchase"
) : ApiException(
    httpStatus = HttpStatusCode.Conflict,
    errorCode = "could_not_verify_purchase",
    errorMessage = errorMessage
)

// Uncategorized errors
class InternalServerErrorException(
    errorMessage: String = "An unexpected error occurred"
) : ApiException(
    httpStatus = HttpStatusCode.InternalServerError,
    errorCode = "unknown",
    errorMessage = errorMessage
)