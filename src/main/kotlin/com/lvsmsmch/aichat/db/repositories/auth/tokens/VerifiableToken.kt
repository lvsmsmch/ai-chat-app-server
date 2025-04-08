package com.lvsmsmch.aichat.db.repositories.auth.tokens

import com.lvsmsmch.aichat.network.routing.auth.UnauthorizedException
import com.lvsmsmch.aichat.utils.UtcTimestamp
import io.ktor.server.application.*
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq

interface TokenDbo {
    val token: String
    val createdAt: UtcTimestamp
    val expiresAt: UtcTimestamp
}

interface VerifiableToken<T : TokenDbo> {

    val collection: CoroutineCollection<T>

    suspend fun verifyToken(call: ApplicationCall): T {
        val authHeader = call.request.headers["Authorization"]
            ?: throw UnauthorizedException("Missing Authorization header")

        if (!authHeader.startsWith("Bearer ")) {
            throw UnauthorizedException("Invalid Authorization format. Must use Bearer token")
        }

        val token = authHeader.removePrefix("Bearer ").trim()
        if (token.isEmpty()) {
            throw UnauthorizedException("Empty authentication token")
        }

        val authToken = collection.findOne(
            TokenDbo::token eq token
        ) ?: throw UnauthorizedException("Invalid authentication token")

        if (authToken.expiresAt.isInPast()) {
            throw UnauthorizedException("Authentication token has expired")
        }

        return authToken
    }
}