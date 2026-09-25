package com.lvsmsmch.aichat.billing

import com.google.auth.oauth2.GoogleCredentials
import com.lvsmsmch.aichat.utils.logger
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.FileInputStream

sealed interface PurchaseCheck {
    /** Подписка живая (или в грейс-периоде — доступ ещё есть). */
    data object Active : PurchaseCheck
    /** Google знает эту покупку, но она больше не действует. */
    data object Inactive : PurchaseCheck
    /** Сервисный аккаунт не настроен — проверять нечем. */
    data object NotConfigured : PurchaseCheck
    data class Error(val message: String) : PurchaseCheck
}

/**
 * Проверка покупки в Google Play Developer API.
 *
 * Клиенту верить нельзя: токен покупки можно подделать в модифицированном
 * приложении, поэтому подписку подтверждает Google, а не наш экран оплаты.
 *
 * Ключ берётся из env PLAY_SERVICE_ACCOUNT (путь к json сервисного аккаунта,
 * которому в Play Console выдан доступ к финансовым данным приложения). Ключа
 * нет — проверка возвращает [PurchaseCheck.NotConfigured], и решение, что
 * делать дальше, принимает вызывающий.
 */
class PlayPurchaseVerifier(private val client: HttpClient) {

    private val packageName: String =
        System.getenv("PLAY_PACKAGE_NAME") ?: "com.lvsmsmch.aichat"

    private val credentials: GoogleCredentials? by lazy {
        val path = System.getenv("PLAY_SERVICE_ACCOUNT")
        if (path.isNullOrBlank()) {
            logger.warn("PLAY_SERVICE_ACCOUNT not set - purchases are not verified with Google")
            return@lazy null
        }
        runCatching {
            GoogleCredentials.fromStream(FileInputStream(path))
                .createScoped(listOf("https://www.googleapis.com/auth/androidpublisher"))
        }.onFailure { logger.error("Failed to read Play service account: ${it.message}", it) }
            .getOrNull()
    }

    suspend fun verify(purchaseToken: String): PurchaseCheck {
        val creds = credentials ?: return PurchaseCheck.NotConfigured
        val accessToken = runCatching {
            creds.refreshIfExpired()
            creds.accessToken.tokenValue
        }.getOrElse { return PurchaseCheck.Error("auth failed: ${it.message}") }

        val url = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" +
            "$packageName/purchases/subscriptionsv2/tokens/$purchaseToken"

        val response = runCatching {
            client.get(url) { header(HttpHeaders.Authorization, "Bearer $accessToken") }
        }.getOrElse { return PurchaseCheck.Error("request failed: ${it.message}") }

        if (response.status != HttpStatusCode.OK) {
            return PurchaseCheck.Error("Play answered ${response.status}")
        }
        val state = runCatching {
            Json.parseToJsonElement(response.bodyAsText())
                .jsonObject["subscriptionState"]?.jsonPrimitive?.content
        }.getOrNull()

        // Грейс-период — это просроченный платёж, но доступ Google сохраняет,
        // поэтому забирать премиум раньше самого Google не будем
        return if (state == "SUBSCRIPTION_STATE_ACTIVE" || state == "SUBSCRIPTION_STATE_IN_GRACE_PERIOD") {
            PurchaseCheck.Active
        } else {
            logger.info("Subscription token is not active: $state")
            PurchaseCheck.Inactive
        }
    }
}
