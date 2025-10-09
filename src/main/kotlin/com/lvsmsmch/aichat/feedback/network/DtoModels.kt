package com.lvsmsmch.aichat.feedback.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SendFeedbackRequest(
    @SerialName("text") val text: String
)