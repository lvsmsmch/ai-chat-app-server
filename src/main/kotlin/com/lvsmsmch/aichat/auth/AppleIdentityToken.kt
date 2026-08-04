package com.lvsmsmch.aichat.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.lvsmsmch.aichat.utils.OAuthException
import com.lvsmsmch.aichat.utils.logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Что удалось достать из identity-токена Apple.
 *
 * [email] может быть null: Apple отдаёт адрес ТОЛЬКО при первой авторизации
 * приложения, дальше в токене остаётся один [appleUserId]. Поэтому аккаунт
 * привязывается к нему, а не к почте.
 *
 * Адрес может оказаться скрытым (`...@privaterelay.appleid.com`) — это рабочий
 * адрес, письма через релей доходят, отличать его от обычного не нужно.
 */
data class AppleUserData(
    val appleUserId: String,
    val email: String?,
    val emailVerified: Boolean,
)

/**
 * Проверка identity-токена Apple Sign In.
 *
 * Клиент присылает JWT, подписанный ключом Apple. Доверять его содержимому
 * можно только после проверки подписи ключом из Apple JWKS плюс проверки, что
 * токен выпущен Apple ([APPLE_ISSUER]) и предназначен НАШЕМУ приложению
 * (`aud` — один из [APPLE_CLIENT_IDS]). Без проверки `aud` подошёл бы токен,
 * выданный любому другому приложению.
 *
 * Ключи Apple ротируются, поэтому JWKS кешируется на [KEYS_TTL_MS] и
 * перезапрашивается сразу, если в токене пришёл неизвестный `kid`.
 */
class AppleIdentityTokenVerifier(private val client: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    private data class CachedKeys(val keys: Map<String, RSAPublicKey>, val fetchedAt: Long)

    private val cache = AtomicReference<CachedKeys?>(null)

    suspend fun verify(identityToken: String): AppleUserData {
        val clientIds = configuredClientIds()
        if (clientIds.isEmpty()) {
            throw OAuthException("Apple sign-in is not configured on the server")
        }

        val decoded = runCatching { JWT.decode(identityToken) }.getOrElse {
            throw OAuthException("Malformed Apple identity token")
        }
        val kid = decoded.keyId ?: throw OAuthException("Apple identity token has no kid")

        val key = publicKey(kid, allowRefresh = true)
            ?: throw OAuthException("Unknown Apple signing key")

        val verified = runCatching {
            JWT.require(Algorithm.RSA256(key, null))
                .withIssuer(APPLE_ISSUER)
                // Любой из наших client id: у iOS-приложения и у веба они разные
                .withAnyOfAudience(*clientIds.toTypedArray())
                .build()
                .verify(identityToken)
        }.getOrElse { e ->
            logger.warn("Apple identity token отклонён: ${e.message}")
            throw OAuthException("Invalid Apple identity token")
        }

        val sub = verified.subject?.takeIf { it.isNotBlank() }
            ?: throw OAuthException("Apple identity token has no subject")

        val email = verified.getClaim("email").asString()?.takeIf { it.isNotBlank() }
        // Apple присылает признак и булевым, и строкой "true" — учитываем оба
        val emailVerifiedClaim = verified.getClaim("email_verified")
        val emailVerified = emailVerifiedClaim.asBoolean()
            ?: (emailVerifiedClaim.asString()?.equals("true", ignoreCase = true) ?: false)

        return AppleUserData(
            appleUserId = sub,
            email = email?.let { Emails.normalize(it) },
            emailVerified = emailVerified,
        )
    }

    /** Ключ по kid: сначала из кеша, при промахе — свежий JWKS (один раз). */
    private suspend fun publicKey(kid: String, allowRefresh: Boolean): RSAPublicKey? {
        val cached = cache.get()
        val fresh = cached != null &&
            System.currentTimeMillis() - cached.fetchedAt < KEYS_TTL_MS
        if (cached != null && (fresh || !allowRefresh)) {
            cached.keys[kid]?.let { return it }
            if (!allowRefresh) return null
        }
        val keys = fetchKeys() ?: return cached?.keys?.get(kid)
        cache.set(CachedKeys(keys, System.currentTimeMillis()))
        return keys[kid]
    }

    private suspend fun fetchKeys(): Map<String, RSAPublicKey>? = runCatching {
        val body = client.get(APPLE_KEYS_URL).bodyAsText()
        json.parseToJsonElement(body).jsonObject["keys"]!!.jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            val kid = obj["kid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val n = obj["n"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val e = obj["e"]?.jsonPrimitive?.content ?: return@mapNotNull null
            kid to rsaKey(n, e)
        }.toMap()
    }.getOrElse { e ->
        logger.error("Не удалось получить ключи Apple: ${e.message}", e)
        null
    }

    /** Модуль и экспонента приходят base64url — собираем из них публичный ключ. */
    private fun rsaKey(nBase64Url: String, eBase64Url: String): RSAPublicKey {
        val decoder = Base64.getUrlDecoder()
        val modulus = BigInteger(1, decoder.decode(nBase64Url))
        val exponent = BigInteger(1, decoder.decode(eBase64Url))
        return KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
    }

    companion object {
        private const val APPLE_ISSUER = "https://appleid.apple.com"
        private const val APPLE_KEYS_URL = "https://appleid.apple.com/auth/keys"
        private const val KEYS_TTL_MS = 6 * 60 * 60 * 1000L

        /**
         * Идентификаторы, для которых Apple выпускает токены: bundle id
         * iOS-приложения и, если появится вход с сайта, Services ID. Список
         * через запятую в APPLE_CLIENT_IDS.
         */
        fun configuredClientIds(): List<String> =
            System.getenv("APPLE_CLIENT_IDS")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

        fun isConfigured(): Boolean = configuredClientIds().isNotEmpty()
    }
}
