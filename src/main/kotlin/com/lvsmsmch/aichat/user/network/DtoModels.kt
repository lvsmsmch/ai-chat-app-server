package com.lvsmsmch.aichat.user.network

import com.lvsmsmch.aichat.character.network.CharacterDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============= RESPONSE MODELS =============

@Serializable
data class UserDto(
    @SerialName("id") val id: String,
    @SerialName("username") val username: String,
    @SerialName("name") val name: String? = null,
    @SerialName("profilePicUrl") val profilePicUrl: String? = null
)

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

@Serializable
data class UserFullInfoDto(
    @SerialName("user") val user: UserDto,
    @SerialName("userDetails") val userDetails: UserDetailsDto
)

@Serializable
data class FollowerDto(
    @SerialName("follower") val follower: UserDto,
    @SerialName("followedAt") val followedAt: String,
)

@Serializable
data class FollowingDto(
    @SerialName("following") val following: UserDto,
    @SerialName("followedAt") val followedAt: String,
)

@Serializable
data class FollowersResponse(
    @SerialName("followers") val followers: List<FollowerDto>,
    @SerialName("nextCursor") val nextCursor: String? = null,
    @SerialName("hasMore") val hasMore: Boolean = false
)

@Serializable
data class FollowingResponse(
    @SerialName("following") val following: List<FollowingDto>,
    @SerialName("nextCursor") val nextCursor: String? = null,
    @SerialName("hasMore") val hasMore: Boolean = false
)

@Serializable
data class UserCharactersResponse(
    @SerialName("characters") val characters: List<CharacterDto>,
    @SerialName("nextCursor") val nextCursor: String? = null,
    @SerialName("hasMore") val hasMore: Boolean = false
)

// ============= REQUEST MODELS =============

@Serializable
data class GetUserCharactersRequest(
    @SerialName("visibility") val visibility: Int? = null,
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("size") val size: Int = 10
)

@Serializable
data class GetFollowersRequest(
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("size") val size: Int = 10
)

@Serializable
data class GetFollowingRequest(
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("size") val size: Int = 10
)

@Serializable
data class ReportUserRequest(
    @SerialName("reason") val reason: String,
    @SerialName("text") val text: String = ""
)