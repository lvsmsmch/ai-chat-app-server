package com.lvsmsmch.aichat.user.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FollowingResponse(
    @SerialName("following") val following: List<FollowingDto>,
    @SerialName("nextCursor") val nextCursor: String? = null,
    @SerialName("hasMore") val hasMore: Boolean = false
)