package com.lvsmsmch.aichat.auth.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserPrivateInfoDto(
    @SerialName("id") val id: String,
    @SerialName("isGuest") val isGuest: Boolean
)