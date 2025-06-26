package com.lvsmsmch.aichat.user.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FollowerDto(
    @SerialName("follower") val follower: UserDto,
    @SerialName("followedAt") val followedAt: String,
)