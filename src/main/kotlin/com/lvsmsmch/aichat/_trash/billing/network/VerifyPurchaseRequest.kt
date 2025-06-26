package com.lvsmsmch.aichat._trash.billing.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VerifyPurchaseRequest(
    @SerialName("purchaseToken") val purchaseToken: String,
    @SerialName("productId") val productId: String,
    @SerialName("orderId") val orderId: String,
    @SerialName("packageName") val packageName: String,
    @SerialName("backgroundImageId") val backgroundImageId: String
)