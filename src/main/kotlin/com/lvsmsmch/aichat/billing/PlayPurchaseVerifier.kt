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

    private companion object {
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 700L
    }

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

        // Короткие перебои у Google — обычное дело, а цена ошибки здесь высокая:
        // человек уже заплатил. Пара повторов снимает почти все случайные сбои,
        // не затягивая запрос настолько, чтобы клиент отвалился по таймауту
        var response: io.ktor.client.statement.HttpResponse? = null
        var lastError = "no attempts"
        for (attempt in 0 until ATTEMPTS) {
            if (attempt > 0) kotlinx.coroutines.delay(RETRY_DELAY_MS)
            val call = runCatching {
                client.get(url) { header(HttpHeaders.Authorization, "Bearer $accessToken") }
            }
            val attempted = call.getOrNull()
            if (attempted == null) {
                lastError = "request failed: ${call.exceptionOrNull()?.message}"
                continue
            }
            // 4xx — это ответ по существу (нет такой покупки, нет прав), его
            // повтор не исправит. Повторяем только серверные сбои
            if (attempted.status.value < 500) {
                response = attempted
                break
            }
            lastError = "Play answered ${attempted.status}"
        }

        val answer = response ?: return PurchaseCheck.Error(lastError)
        if (answer.status != HttpStatusCode.OK) {
            return PurchaseCheck.Error("Play answered ${answer.status}")
        }

        val state = runCatching {
            Json.parseToJsonElement(answer.bodyAsText())
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
