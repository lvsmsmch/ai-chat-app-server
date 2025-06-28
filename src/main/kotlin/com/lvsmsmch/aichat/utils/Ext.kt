package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.auth.database.tokens.TokenDbo
import com.lvsmsmch.aichat.auth.database.tokens.TokenRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.litote.kmongo.coroutine.CoroutineCollection
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import kotlin.random.Random

val defaultJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

suspend inline fun ApplicationCall.respondSuccess() {
    this.respond(
        HttpStatusCode.OK,
        ApiResponse<Nothing>(status = HttpStatusCode.OK.value)
    )
}

suspend inline fun <reified T> ApplicationCall.respondSuccess(data: T) {
    this.respond(
        HttpStatusCode.OK,
        ApiResponse(status = HttpStatusCode.OK.value, data = data)
    )
}

suspend fun ApplicationCall.respondError(httpStatusCode: HttpStatusCode, code: String, message: String) {
    this.respond(
        httpStatusCode,
        ApiResponse<Nothing>(status = httpStatusCode.value, error = ErrorDetails(code, message))
    )
}

fun ApplicationCall.getUserIp(): String {
    val forwardedForHeader = request.header("X-Forwarded-For")
    if (!forwardedForHeader.isNullOrBlank()) {
        return forwardedForHeader.split(",")[0].trim()
    }
    return request.origin.remoteHost
}

fun loadConfig(): Properties {
    val properties = Properties()
    val inputStream = Thread.currentThread().contextClassLoader.getResourceAsStream("config.properties")
        ?: throw IllegalArgumentException("config.properties not found")

    properties.load(inputStream)
    return properties
}

fun generateToken(): String {
    val secureRandom = SecureRandom()
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

fun generateVerificationCode(): Int {
    return Random.nextInt(100_000, 999_999)
}

fun hashPassword(providedPassword: String): String {
    return BCrypt.hashpw(providedPassword, BCrypt.gensalt())
}

fun checkPassword(providedPassword: String, storedHash: String): Boolean {
    return BCrypt.checkpw(providedPassword, storedHash)
}

fun generateUniqueUsername(): String {
    val adjectives = listOf("Fast", "Cool", "Brave", "Happy", "Sly", "Mighty", "Wild", "Fierce", "Clever")
    val nouns = listOf("Tiger", "Falcon", "Wizard", "Ninja", "Panther", "Wolf", "Phoenix", "Warrior", "Shadow")
    return "${adjectives.random()}${nouns.random()}${(1000..9999).random()}"
}

inline fun <reified T : Any> createDatabaseEventsFlow(
    collection: CoroutineCollection<T>,
): SharedFlow<DatabaseEvent<T>> {
    return collection
        .watchAsFlow()
        .onEach { event -> logDatabaseEvent(event, collection.namespace.collectionName) }
        .shareIn(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            started = SharingStarted.Eagerly,
            replay = 0
        )
}


fun generateHash(hashSize: Int, vararg values: String): String {
    val combined = values.joinToString(":")
    return MessageDigest.getInstance("MD5")
        .digest(combined.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(hashSize)
}

fun Application.logStructuredError(
    call: ApplicationCall,
    errorCode: String,
    statusCode: Int,
    message: String,
    userId: String? = null,
    exception: Throwable? = null
) {
    data class ErrorLogEntry(
        val errorCode: String,
        val statusCode: Int,
        val uri: String,
        val method: String,
        val message: String,
        val correlationId: String? = null,
        val userId: String? = null,
        val stackTrace: String? = null
    )

    val entry = ErrorLogEntry(
        errorCode = errorCode,
        statusCode = statusCode,
        uri = call.request.path(),
        method = call.request.httpMethod.value,
        message = message,
        correlationId = call.request.header("X-Correlation-ID"),
        userId = userId,
        stackTrace = exception?.stackTraceToString()
    )

    this.log.error(Json.encodeToString(entry))
}

suspend fun <T : TokenDbo> PipelineContext<Unit, ApplicationCall>.verifyToken(
    tokenRepository: TokenRepository<T>
): T {
    val authHeader = call.request.headers["Authorization"]
        ?: throw BadRequestException("Missing Authorization header")

    if (!authHeader.startsWith("Bearer ")) {
        throw BadRequestException("Invalid Authorization format. Must use Bearer token")
    }

    val token = authHeader.removePrefix("Bearer ").trim()
    if (token.isEmpty()) {
        throw BadRequestException("Empty authentication token")
    }

    val tokenDbo = tokenRepository.get(token)
    if (tokenDbo == null || tokenDbo.expiresAt.isInPast()) {
        throw TokenExpiredException("Authentication token has expired")
    }

    return tokenDbo
}