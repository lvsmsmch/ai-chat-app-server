package com.lvsmsmch.aichat._trash.billing.network

import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionStatusDto(
    val hasPremium: Boolean,
    val subscriptionType: String?,
    val expiresAt: String?,
    val isAutoRenewing: Boolean
)