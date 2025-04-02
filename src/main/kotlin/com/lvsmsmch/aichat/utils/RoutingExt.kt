/**
 * Public routing functions for auth token handling
 */
package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.db.repositories.session._base.AuthTokenDbo
import com.lvsmsmch.aichat.db.repositories.session._base.AuthTokensBaseRepository
import com.lvsmsmch.aichat.db.repositories.session.single_use.SingleUseAuthTokenDbo
import com.lvsmsmch.aichat.db.repositories.session.single_use.SingleUseAuthTokensRepository
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionDbo
import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.json.Json
import org.bson.types.ObjectId
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * Validates an authentication token and throws exceptions with appropriate error messages
 */
suspend inline fun <T : AuthTokenDbo> PipelineContext<Unit, ApplicationCall>.validateAuthToken(
    repository: AuthTokensBaseRepository<T>
): T {
    // Extract auth token from header
    val authHeader = call.request.headers["Authorization"]
        ?: throw UnauthorizedException("Missing Authorization header")

    if (!authHeader.startsWith("Bearer ")) {
        throw UnauthorizedException("Invalid Authorization format. Must use Bearer token")
    }

    val token = authHeader.removePrefix("Bearer ").trim()
    if (token.isEmpty()) {
        throw UnauthorizedException("Empty authentication token")
    }

    // Verify token
    val authToken = repository.getTokenByTokenString(token)
        ?: throw UnauthorizedException("Invalid authentication token")

    // Check if token is expired
    if (authToken.expiresAt.hasHappened()) {
        throw UnauthorizedException("Authentication token has expired")
    }

    return authToken
}

/**
 * Custom exception for authentication failures
 */
class UnauthorizedException(override val message: String) : Exception(message)


// Private implementation details in a nested object
object RoutingImplementation {
    /**
     * Generic helper function to extract and validate any type of auth token
     */
    suspend inline fun <T : AuthTokenDbo> PipelineContext<Unit, ApplicationCall>.extractAndValidateToken(
        repository: AuthTokensBaseRepository<T>
    ): T? {
        // Extract auth token from header
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            call.respond(HttpStatusCode.Unauthorized, "Missing or invalid Authorization header")
            return null
        }

        val token = authHeader.removePrefix("Bearer ").trim()
        if (token.isEmpty()) {
            call.respond(HttpStatusCode.Unauthorized, "Empty authentication token")
            return null
        }

        // Verify token
        val authToken = repository.getTokenByTokenString(token)
        if (authToken == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid authentication token")
            return null
        }

        // Check if token is expired
        if (authToken.expiresAt.hasHappened()) {
            call.respond(HttpStatusCode.Unauthorized, "Authentication token has expired")
            return null
        }

        return authToken
    }

    // Other implementation details like request parsing functions
    // All moved here to hide them from public API
    suspend inline fun <reified T : Any> ApplicationCall.getRequest(): T {
        val contentType = request.contentType().withoutParameters()

        return when {
            contentType.match("multipart/form-data") -> parseMultipartData<T>(receiveMultipart())
            contentType.match("application/json") -> parseJson<T>(receive<String>())
            else -> throw Exception("Unsupported content type: $contentType")
        }
    }

    suspend inline fun <reified T : Any> ApplicationCall.receiveMultipartCustom(): T {
        val contentType = request.contentType().withoutParameters()
        if (!contentType.match("multipart/form-data")) {
            throw Exception("Unsupported content type: $contentType")
        }
        return parseMultipartData<T>(receiveMultipart())
    }


    inline fun <reified T : Any> parseJson(jsonBody: String): T {
        return Json.decodeFromString<T>(jsonBody)
    }

    suspend inline fun <reified T : Any> parseMultipartData(multipart: MultiPartData): T {
        val fieldMap = mutableMapOf<String, String>()
        var file: File? = null

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FormItem -> fieldMap[part.name ?: ""] = part.value
                is PartData.FileItem -> {
                    val fileBytes = part.streamProvider().readBytes()
                    val fileName = "${ObjectId().toHexString()}.jpg"
                    file = File(fileName).apply { writeBytes(fileBytes) }
                }
                else -> {}
            }
            part.dispose()
        }

        return constructDataClass(T::class, fieldMap, file)
    }

    fun <T : Any> constructDataClass(kClass: KClass<T>, fieldMap: Map<String, String>, file: File?): T {
        val constructor = kClass.primaryConstructor!!
        val parameters = constructor.parameters.associateWith { param ->
            when (param.type.classifier) {
                String::class -> fieldMap[param.name]
                Int::class -> fieldMap[param.name]?.toIntOrNull()
                Long::class -> fieldMap[param.name]?.toLongOrNull()
                Double::class -> fieldMap[param.name]?.toDoubleOrNull()
                Boolean::class -> fieldMap[param.name]?.toBoolean()
                File::class -> file
                else -> null
            }
        }

        return constructor.callBy(parameters)
    }
}

/**
 * Extension function for handling POST requests WITHOUT a request body
 */
@KtorDsl
inline fun Routing.postDefault(
    path: String,
    crossinline body: suspend PipelineContext<Unit, ApplicationCall>.() -> Unit
): Route = post(path) {
    body()
}

/**
 * Extension function for handling POST requests with automatic request body deserialization
 */
@KtorDsl
@JvmName("postDefaultTyped")
inline fun <reified R : Any> Routing.postDefault(
    path: String,
    crossinline body: suspend PipelineContext<Unit, ApplicationCall>.(R) -> Unit
): Route = post(path) {
    try {
        val request = RoutingImplementation.run { call.getRequest<R>() }
        body(request)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, "Invalid request: ${e.message}")
    }
}

/**
 * Generic extension function for handling authenticated POST requests WITHOUT a request body
 */
@KtorDsl
inline fun <T : AuthTokenDbo> Routing.postAuth(
    path: String,
    repository: AuthTokensBaseRepository<T>,
    crossinline body: suspend PipelineContext<Unit, ApplicationCall>.(T) -> Unit
): Route = post(path) {
    val authToken = RoutingImplementation.run {
        extractAndValidateToken(repository)
    } ?: return@post
    body(authToken)
}

/**
 * Generic extension function for handling authenticated POST requests with automatic request body deserialization
 */
@KtorDsl
@JvmName("postAuthTyped")
inline fun <reified R : Any, T : AuthTokenDbo> Routing.postAuth(
    path: String,
    repository: AuthTokensBaseRepository<T>,
    crossinline body: suspend PipelineContext<Unit, ApplicationCall>.(R, T) -> Unit
): Route = post(path) {
    val authToken = RoutingImplementation.run {
        extractAndValidateToken(repository)
    } ?: return@post

    try {
        val request = RoutingImplementation.run { call.getRequest<R>() }
        body(request, authToken)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, "Invalid request: ${e.message}")
    }
}

/**
 * Type-specific extension for UserId token (for backward compatibility)
 */
@KtorDsl
inline fun Routing.postAuth(
    path: String,
    sessionRepository: SessionRepository,
    crossinline body: suspend PipelineContext<Unit, ApplicationCall>.(SessionDbo) -> Unit
): Route = postAuth(path, sessionRepository as AuthTokensBaseRepository<SessionDbo>, body)

/**
 * Type-specific extension for UserId token with request (for backward compatibility)
 */
@KtorDsl
@JvmName("postAuthUserIdTyped")
inline fun <reified R : Any> Routing.postAuth(
    path: String,
    sessionRepository: SessionRepository,
    crossinline body: suspend PipelineContext<Unit, ApplicationCall>.(R, SessionDbo) -> Unit
): Route = postAuth(path, sessionRepository as AuthTokensBaseRepository<SessionDbo>, body)

/**
 * Type-specific extension for Email token (for backward compatibility)
 */
@KtorDsl
inline fun Routing.postAuth(
    path: String,
    singleUseAuthTokensRepository: SingleUseAuthTokensRepository,
    crossinline body: suspend PipelineContext<Unit, ApplicationCall>.(SingleUseAuthTokenDbo) -> Unit
): Route = postAuth(path, singleUseAuthTokensRepository as AuthTokensBaseRepository<SingleUseAuthTokenDbo>, body)

/**
 * Type-specific extension for Email token with request (for backward compatibility)
 */
@KtorDsl
@JvmName("postAuthEmailTyped")
inline fun <reified R : Any> Routing.postAuth(
    path: String,
    singleUseAuthTokensRepository: SingleUseAuthTokensRepository,
    crossinline body: suspend PipelineContext<Unit, ApplicationCall>.(R, SingleUseAuthTokenDbo) -> Unit
): Route = postAuth(path, singleUseAuthTokensRepository as AuthTokensBaseRepository<SingleUseAuthTokenDbo>, body)