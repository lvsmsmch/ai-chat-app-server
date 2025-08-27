package com.lvsmsmch.aichat.auth.network

import com.lvsmsmch.aichat.user.network.UserDetailsDto
import com.lvsmsmch.aichat.user.network.UserDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============= RESPONSE MODELS =============

@Serializable
data class UserLoginInfoDto(
    @SerialName("id") val id: String,
    @SerialName("sessionToken") val sessionToken: String
)

@Serializable
data class UserPrivateInfoDto(
    @SerialName("id") val id: String,
    @SerialName("isGuest") val isGuest: Boolean
)

@Serializable
data class SuccessfulLoginDto(
    @SerialName("userLoginInfo") val userLoginInfoDto: UserLoginInfoDto,
    @SerialName("userPrivateInfo") val userPrivateInfoDto: UserPrivateInfoDto,
    @SerialName("userDto") val userDto: UserDto,
    @SerialName("userDetailsDto") val userDetailsDto: UserDetailsDto,
)

// ============= REQUEST MODELS =============

@Serializable
data class GoogleAuthRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("googleToken") val googleToken: String
)

@Serializable
data class GuestAuthRequest(
    @SerialName("deviceId") val deviceId: String
)

@Serializable
data class SubscriptionStatusRequest(
    @SerialName("hasSubscription") val hasSubscription: Boolean
)

// ============= INTERNAL MODELS =============

data class OAuthUserData(
    val id: String,
    val email: String?,
    val name: String?,
    val profilePictureUrl: String?
)