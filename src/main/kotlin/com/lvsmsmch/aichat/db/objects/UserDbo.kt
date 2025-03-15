package com.lvsmsmch.aichat.db.objects

import com.lvsmsmch.aichat.db.utils.UtcTimestamp
import kotlinx.serialization.Serializable

@Serializable
data class UserDbo(
    val id: String,
    val createdAt: UtcTimestamp,
    val isVerified: Boolean,
    val loginMethod: String,
    val email: String,
    val hashedPassword: String,
    val oauthId: String,
    val name: String,
    val profilePicUrl: String,
)