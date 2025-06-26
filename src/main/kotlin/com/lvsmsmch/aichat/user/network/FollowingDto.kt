package com.lvsmsmch.aichat.user.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FollowingDto(
    @SerialName("following") val following: UserDto,
    @SerialName("followedAt") val followedAt: String,
)