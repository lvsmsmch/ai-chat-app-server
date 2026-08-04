package com.lvsmsmch.aichat.auth.network

import com.lvsmsmch.aichat.user.network.UserDetailsDto
import com.lvsmsmch.aichat.user.network.UserDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


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
    /** Лимиты + статус подписки сразу при входе — профиль не ждёт второй запрос. */
    @SerialName("limits") val limitsResponse: com.lvsmsmch.aichat.chat.network.LimitsResponse? = null,
)


@Serializable
data class GoogleAuthRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("googleToken") val googleToken: String
)

@Serializable
data class GoogleConnectRequest(
    @SerialName("googleToken") val googleToken: String
)

@Serializable
data class GuestAuthRequest(
    @SerialName("deviceId") val deviceId: String
)

// ---- Вход через Apple ----

@Serializable
data class AppleAuthRequest(
    @SerialName("identityToken") val identityToken: String,
    @SerialName("deviceId") val deviceId: String,
    /** Apple отдаёт имя только при первой авторизации и не в токене. */
    @SerialName("fullName") val fullName: String? = null,
)

@Serializable
data class AppleConnectRequest(
    @SerialName("identityToken") val identityToken: String,
)

// ---- Вход по почте и паролю ----

@Serializable
data class EmailRegisterRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
    @SerialName("deviceId") val deviceId: String,
)

@Serializable
data class EmailLoginRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
    @SerialName("deviceId") val deviceId: String,
)

@Serializable
data class EmailVerifyRequest(
    @SerialName("code") val code: String,
)

@Serializable
data class ForgotPasswordRequest(
    @SerialName("email") val email: String,
)

@Serializable
data class ResetPasswordRequest(
    @SerialName("email") val email: String,
    @SerialName("code") val code: String,
    @SerialName("newPassword") val newPassword: String,
)

@Serializable
data class ChangePasswordRequest(
    @SerialName("currentPassword") val currentPassword: String,
    @SerialName("newPassword") val newPassword: String,
)

@Serializable
data class LinkEmailRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
)

@Serializable
data class SubscriptionStatusRequest(
    @SerialName("hasSubscription") val hasSubscription: Boolean
)


data class OAuthUserData(
    val id: String,
    val email: String?,
    val name: String?,
    val profilePictureUrl: String?
)