package com.lvsmsmch.aichat.app_data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============= RESPONSE MODELS =============

@Serializable
data class PaywallImagesResponse(
    @SerialName("images") val images: List<String>
)

// ============= REQUEST MODELS =============
