package com.lvsmsmch.aichat.utils

import io.ktor.http.*

abstract class ApiException(
    val httpStatus: HttpStatusCode,
    val errorCode: String,
    val errorMessage: String = ""
) : RuntimeException(errorMessage)

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

// Validation errors
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

// User-specific errors
class UserNotFoundException(
    id: String? = null,
    errorMessage: String = if (id != null) "User with id $id not found" else "User not found"
) : ApiException(
    httpStatus = HttpStatusCode.NotFound,
    errorCode = "user_not_found",
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

// Uncategorized errors
class InternalServerErrorException(
    errorMessage: String = "An unexpected error occurred"
) : ApiException(
    httpStatus = HttpStatusCode.InternalServerError,
    errorCode = "unknown",
    errorMessage = errorMessage
)