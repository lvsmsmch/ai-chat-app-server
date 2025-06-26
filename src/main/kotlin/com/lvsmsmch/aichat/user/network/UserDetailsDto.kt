package com.lvsmsmch.aichat.user.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDetailsDto(
    @SerialName("id") val id: String,
    @SerialName("lastActiveAt") val lastActiveAt: String,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("bio") val bio: String? = null,
    @SerialName("publicCharactersCount") val publicCharactersCount: Int = 0,
    @SerialName("privateCharactersCount") val privateCharactersCount: Int = 0,
    @SerialName("followersCount") val followersCount: Int = 0,
    @SerialName("followingCount") val followingCount: Int = 0,
    @SerialName("isFollowing") val isFollowing: Boolean = false
)