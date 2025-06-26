package com.lvsmsmch.aichat.auth.database.tokens

import com.lvsmsmch.aichat.utils.TokenExpiredException
import com.lvsmsmch.aichat.utils.UtcTimestamp
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq

interface TokenDbo {
    val token: String
    val createdAt: UtcTimestamp
    val expiresAt: UtcTimestamp
}

interface TokenRepository<T : TokenDbo> {

    val collection: CoroutineCollection<T>

    suspend fun get(token: String): T? {
        return collection.findOne(TokenDbo::token eq token)
    }

    suspend fun delete(token: String) {
        collection.deleteOne(TokenDbo::token eq token)
    }

    suspend fun isTokenNullOrExpired(token: String): Boolean {
        val authToken = collection.findOne(TokenDbo::token eq token)
        return authToken == null || authToken.expiresAt.isInPast()
    }

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

        val authToken = collection.findOne(TokenDbo::token eq token)
        if (authToken == null || authToken.expiresAt.isInPast()) {
            throw TokenExpiredException("Authentication token has expired")
        }

        return authToken
    }
}