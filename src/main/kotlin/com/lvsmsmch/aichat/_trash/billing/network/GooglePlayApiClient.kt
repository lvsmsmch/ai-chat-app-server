package com.lvsmsmch.aichat._trash.billing.network

import com.google.auth.oauth2.ServiceAccountCredentials
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*

class GooglePlayApiClient {

    companion object {
        private const val GOOGLE_PLAY_API_BASE_URL = "https://androidpublisher.googleapis.com"
        private const val SERVICE_ACCOUNT_KEY_FILE = "google-play-service-account.json"
        private const val SCOPE = "https://www.googleapis.com/auth/androidpublisher"
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    private suspend fun getAccessToken(): String {
        // Load service account credentials
        val credentials = ServiceAccountCredentials.fromStream(
            this::class.java.classLoader.getResourceAsStream(SERVICE_ACCOUNT_KEY_FILE)
        )

        val scopedCredentials = credentials.createScoped(listOf(SCOPE))
        scopedCredentials.refresh()

        return scopedCredentials.accessToken.tokenValue
    }

    suspend fun verifySubscription(
        packageName: String,
        subscriptionId: String,
        purchaseToken: String
    ): GooglePlaySubscriptionResponse? {
        return try {
            val accessToken = getAccessToken()

            val response = httpClient.get(
                "$GOOGLE_PLAY_API_BASE_URL/androidpublisher/v3/applications/$packageName/purchases/subscriptions/$subscriptionId/tokens/$purchaseToken"
            ) {
                headers {
                    append("Authorization", "Bearer $accessToken")
                }
            }

            if (response.status.isSuccess()) {
                response.body<GooglePlaySubscriptionResponse>()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun verifyProduct(
        packageName: String,
        productId: String,
        purchaseToken: String
    ): GooglePlaySubscriptionResponse? {
        return try {
            val accessToken = getAccessToken()

            val response = httpClient.get(
                "$GOOGLE_PLAY_API_BASE_URL/androidpublisher/v3/applications/$packageName/purchases/products/$productId/tokens/$purchaseToken"
            ) {
                headers {
                    append("Authorization", "Bearer $accessToken")
                }
            }

            if (response.status.isSuccess()) {
                response.body<GooglePlaySubscriptionResponse>()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}