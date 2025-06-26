package com.lvsmsmch.aichat.user.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FollowersResponse(
    @SerialName("followers") val followers: List<FollowerDto>,
    @SerialName("nextCursor") val nextCursor: String? = null,
    @SerialName("hasMore") val hasMore: Boolean = false
)