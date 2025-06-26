package com.lvsmsmch.aichat.utils

import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*

fun Application.configureErrorHandling() {
    install(StatusPages) {
        // Handle our custom application exceptions
        exception<ApiException> { call, exception ->
            // Log the error with context
            call.application.log.error(
                "Application exception: ${exception.errorCode} (${exception.httpStatus}) - " +
                        "Path: ${call.request.path()}, Message: ${exception.errorMessage}"
            )

            // Respond with specific error details
            call.respondError(
                httpStatusCode = exception.httpStatus,
                code = exception.errorCode,
                message = exception.errorMessage
            )
        }

        // General catch-all for unexpected exceptions
        exception<Throwable> { call, exception ->
            call.application.log.error(
                "Unhandled exception for ${call.request.path()}: ${exception.message}",
                exception
            )
            call.respondError(
                httpStatusCode = InternalServerErrorException().httpStatus,
                code = InternalServerErrorException().errorCode,
                message = InternalServerErrorException().errorMessage
            )
        }
    }
}