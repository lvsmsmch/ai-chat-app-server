package com.lvsmsmch.aichat.utils

import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*

fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, exception ->
            logger.error(
                "Application exception: ${exception.errorCode} (${exception.httpStatus}) - " +
                        "Path: ${call.request.path()}, Message: ${exception.errorMessage}"
            )

            call.respondError(
                httpStatusCode = exception.httpStatus,
                code = exception.errorCode,
                message = exception.errorMessage
            )
        }

        exception<Throwable> { call, exception ->
            logger.error(
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