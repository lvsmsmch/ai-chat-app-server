package com.lvsmsmch.aichat.auth.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserLoginInfoDto(
    @SerialName("id") val id: String,
    @SerialName("sessionToken") val sessionToken: String
)