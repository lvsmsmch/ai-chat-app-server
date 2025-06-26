package com.lvsmsmch.aichat._trash.billing.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

@Serializable
data class UserSubscription(
    @BsonId @SerialName("_id")  val id: String = ObjectId().toHexString(),
    @SerialName("userId") val userId: String,
    @SerialName("subscriptionType") val subscriptionType: String, // monthly, yearly, lifetime
    @SerialName("productId") val productId: String, // premium_monthly, premium_yearly
    @SerialName("purchaseToken") val purchaseToken: String,
    @SerialName("orderId") val orderId: String,
    @SerialName("packageName") val packageName: String,
    @SerialName("purchaseTimeMillis") val purchaseTimeMillis: Long,
    @SerialName("expiryTimeMillis") val expiryTimeMillis: Long?,
    @SerialName("isAutoRenewing") val isAutoRenewing: Boolean = false,
    @SerialName("acknowledgmentState") val acknowledgmentState: Int = 0,
    @SerialName("consumptionState") val consumptionState: Int = 0,
    @SerialName("priceAmountMicros") val priceAmountMicros: Long = 0,
    @SerialName("priceCurrencyCode") val priceCurrencyCode: String = "UAH",
    @SerialName("createdAt") val createdAt: UtcTimestamp = UtcTimestamp.now(),
    @SerialName("updatedAt") val updatedAt: UtcTimestamp = UtcTimestamp.now(),
    @SerialName("isActive") val isActive: Boolean = true
)