package com.lvsmsmch.aichat._trash.billing.database

import com.lvsmsmch.aichat._trash.billing.network.GooglePlayApiClient
import com.lvsmsmch.aichat._trash.billing.network.SubscriptionStatusDto
import com.lvsmsmch.aichat._trash.billing.network.VerifyPurchaseRequest
import com.lvsmsmch.aichat.utils.UtcTimestamp

class BillingService(
    private val subscriptionRepository: SubscriptionRepository,
    private val googlePlayApiClient: GooglePlayApiClient
) {

    suspend fun verifyPurchase(
        userId: String,
        request: VerifyPurchaseRequest
    ): SubscriptionStatusDto? {

        try {
            // Проверяем, не использовался ли уже этот токен
            val existingSubscription = subscriptionRepository.getSubscriptionByToken(request.purchaseToken)
            if (existingSubscription != null && existingSubscription.userId != userId) {
                // Токен уже использован другим пользователем - подозрительно
                logVerificationAttempt(userId, request, "failed", "Token already used by another user")
                return null
            }

            // Проверяем покупку у Google
            val googleResponse = googlePlayApiClient.verifySubscription(
                packageName = request.packageName,
                subscriptionId = request.productId,
                purchaseToken = request.purchaseToken
            )

            if (googleResponse == null) {
                logVerificationAttempt(userId, request, "failed", "Google verification failed")
                return null
            }

            // Проверяем статус платежа
            if (googleResponse.paymentState != 1) { // 1 = payment received
                logVerificationAttempt(userId, request, "failed", "Payment not received")
                return null
            }

            // Создаем/обновляем подписку
            val subscription = UserSubscription(
                userId = userId,
                subscriptionType = getSubscriptionType(request.productId),
                productId = request.productId,
                purchaseToken = request.purchaseToken,
                orderId = request.orderId,
                packageName = request.packageName,
                purchaseTimeMillis = googleResponse.startTimeMillis.toLong(),
                expiryTimeMillis = googleResponse.expiryTimeMillis.toLongOrNull(),
                isAutoRenewing = googleResponse.autoRenewing,
                acknowledgmentState = googleResponse.acknowledgementState,
                priceAmountMicros = googleResponse.priceAmountMicros.toLong(),
                priceCurrencyCode = googleResponse.priceCurrencyCode
            )

            subscriptionRepository.saveSubscription(subscription)
            logVerificationAttempt(userId, request, "success", null)

            return createSubscriptionStatus(subscription)

        } catch (e: Exception) {
            logVerificationAttempt(userId, request, "failed", "Exception: ${e.message}")
            return null
        }
    }

    suspend fun getSubscriptionStatus(userId: String): SubscriptionStatusDto {
        val subscription = subscriptionRepository.getUserSubscription(userId)

        return if (subscription != null && isSubscriptionActive(subscription)) {
            createSubscriptionStatus(subscription)
        } else {
            SubscriptionStatusDto(
                hasPremium = false,
                subscriptionType = null,
                expiresAt = null,
                isAutoRenewing = false
            )
        }
    }

    suspend fun refreshSubscriptionStatus(userId: String): SubscriptionStatusDto {
        val subscription = subscriptionRepository.getUserSubscription(userId)

        if (subscription != null) {
            // Перепроверяем у Google актуальный статус
            val googleResponse = googlePlayApiClient.verifySubscription(
                packageName = subscription.packageName,
                subscriptionId = subscription.productId,
                purchaseToken = subscription.purchaseToken
            )

            if (googleResponse != null) {
                // Обновляем данные подписки
                val updatedSubscription = subscription.copy(
                    expiryTimeMillis = googleResponse.expiryTimeMillis.toLongOrNull(),
                    isAutoRenewing = googleResponse.autoRenewing,
                    acknowledgmentState = googleResponse.acknowledgementState,
                    updatedAt = UtcTimestamp.now()
                )

                subscriptionRepository.saveSubscription(updatedSubscription)

                return createSubscriptionStatus(updatedSubscription)
            }
        }

        return SubscriptionStatusDto(
            hasPremium = false,
            subscriptionType = null,
            expiresAt = null,
            isAutoRenewing = false
        )
    }

    private fun isSubscriptionActive(subscription: UserSubscription): Boolean {
        val now = UtcTimestamp.now().instant.toEpochMilli()

        return subscription.isActive &&
                (subscription.expiryTimeMillis == null || subscription.expiryTimeMillis > now)
    }

    private fun createSubscriptionStatus(subscription: UserSubscription): SubscriptionStatusDto {
        return SubscriptionStatusDto(
            hasPremium = isSubscriptionActive(subscription),
            subscriptionType = subscription.subscriptionType,
            expiresAt = subscription.expiryTimeMillis?.let {
                UtcTimestamp.fromEpochMillis(it).toString()
            },
            isAutoRenewing = subscription.isAutoRenewing
        )
    }

    private fun getSubscriptionType(productId: String): String {
        return when (productId) {
            "premium_monthly" -> "monthly"
            "premium_yearly" -> "yearly"
            "premium_lifetime" -> "lifetime"
            else -> "unknown"
        }
    }

    private suspend fun logVerificationAttempt(
        userId: String,
        request: VerifyPurchaseRequest,
        result: String,
        errorMessage: String?
    ) {
        val log = PurchaseVerificationLog(
            userId = userId,
            purchaseToken = request.purchaseToken,
            verificationResult = result,
            errorMessage = errorMessage
        )

        subscriptionRepository.logVerificationAttempt(log)
    }

    // Периодическая задача для проверки истекших подписок
    suspend fun checkExpiredSubscriptions() {
        val expiredSubscriptions = subscriptionRepository.getExpiredSubscriptions()

        expiredSubscriptions.forEach { subscription ->
            // Перепроверяем у Google - возможно подписка была продлена
            val googleResponse = googlePlayApiClient.verifySubscription(
                packageName = subscription.packageName,
                subscriptionId = subscription.productId,
                purchaseToken = subscription.purchaseToken
            )


            if (
                googleResponse != null &&
                googleResponse.expiryTimeMillis.toLong() > UtcTimestamp.now().instant.toEpochMilli()
            ) {
                // Подписка была продлена
                val updatedSubscription = subscription.copy(
                    expiryTimeMillis = googleResponse.expiryTimeMillis.toLong(),
                    isAutoRenewing = googleResponse.autoRenewing,
                    updatedAt = UtcTimestamp.now()
                )
                subscriptionRepository.saveSubscription(updatedSubscription)
            } else {
                // Подписка действительно истекла
                subscriptionRepository.deactivateUserSubscription(subscription.userId)
            }
        }
    }
}
