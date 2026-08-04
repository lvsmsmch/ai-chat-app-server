package com.lvsmsmch.aichat.auth.database.tokens

import com.lvsmsmch.aichat.utils.BadRequestException
import com.lvsmsmch.aichat.utils.InvalidTokenException
import com.lvsmsmch.aichat.utils.TokenExpiredException
import com.lvsmsmch.aichat.utils.UtcTimestamp
import io.ktor.server.application.*

interface TokenDbo {
    val token: String
    val createdAt: String
    val expiresAt: String
}

/**
 * Общая часть работы с токенами: достать по значению, удалить, проверить
 * заголовок Authorization. Само хранилище — за конкретной реализацией
 * (сейчас единственная — сессии в Postgres).
 */
interface TokenRepository<T : TokenDbo> {

    suspend fun get(token: String): T?

    suspend fun delete(token: String)

    suspend fun verifyToken(call: ApplicationCall): T {
        val authHeader = call.request.headers["Authorization"]
            ?: throw BadRequestException("Missing Authorization header")

        if (!authHeader.startsWith("Bearer ")) {
            throw BadRequestException("Invalid Authorization format. Must use Bearer token")
        }

        val token = authHeader.removePrefix("Bearer ").trim()
        if (token.isEmpty()) {
            throw BadRequestException("Empty authentication token")
        }

        val authToken = get(token) ?: throw InvalidTokenException()

        if (UtcTimestamp.parse(authToken.expiresAt).isInPast()) {
            delete(authToken.token)
            throw TokenExpiredException("Authentication token has expired")
        }

        return authToken
    }
}
