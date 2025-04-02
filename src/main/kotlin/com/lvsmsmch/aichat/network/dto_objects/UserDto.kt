package com.lvsmsmch.aichat.network.dto_objects

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String,
    val createdAt: UtcTimestamp,
    val username: String,
    val name: String,
    val profilePicUrl: String,
)