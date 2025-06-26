package com.lvsmsmch.aichat._trash.billing.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GooglePlaySubscriptionResponse(
    @SerialName("kind") val kind: String,
    @SerialName("startTimeMillis") val startTimeMillis: String,
    @SerialName("expiryTimeMillis") val expiryTimeMillis: String,
    @SerialName("autoRenewing") val autoRenewing: Boolean,
    @SerialName("priceCurrencyCode") val priceCurrencyCode: String,
    @SerialName("priceAmountMicros") val priceAmountMicros: String,
    @SerialName("countryCode") val countryCode: String,
    @SerialName("paymentState") val paymentState: Int?, // 0: pending, 1: received
    @SerialName("cancelReason") val cancelReason: Int?, // 0: user, 1: system, 2: replaced, 3: developer
    @SerialName("userCancellationTimeMillis") val userCancellationTimeMillis: String?,
    @SerialName("orderId") val orderId: String,
    @SerialName("acknowledgementState") val acknowledgementState: Int, // 0: not acknowledged, 1: acknowledged
    @SerialName("purchaseType") val purchaseType: Int? // 0: test, 1: promo, 2: subscription
)