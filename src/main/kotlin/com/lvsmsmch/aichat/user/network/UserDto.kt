package com.lvsmsmch.aichat.user.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    @SerialName("id") val id: String,
    @SerialName("username") val username: String,
    @SerialName("name") val name: String? = null,
    @SerialName("profilePicUrl") val profilePicUrl: String? = null
)