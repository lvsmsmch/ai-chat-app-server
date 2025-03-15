package com.lvsmsmch.aichat.db.objects

import com.lvsmsmch.aichat.db.utils.UtcTimestamp
import kotlinx.serialization.Serializable

@Serializable
data class SessionDbo(
    val authToken: String,
    val userId: String,
    val expiresAt: UtcTimestamp
)