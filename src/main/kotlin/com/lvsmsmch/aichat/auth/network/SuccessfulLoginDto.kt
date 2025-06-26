package com.lvsmsmch.aichat.auth.network

import com.lvsmsmch.aichat.user.network.UserDetailsDto
import com.lvsmsmch.aichat.user.network.UserDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SuccessfulLoginDto(
    @SerialName("userLoginInfo") val userLoginInfoDto: UserLoginInfoDto,
    @SerialName("userPrivateInfo") val userPrivateInfoDto: UserPrivateInfoDto,
    @SerialName("userDto") val userDto: UserDto,
    @SerialName("userDetailsDto") val userDetailsDto: UserDetailsDto,
)