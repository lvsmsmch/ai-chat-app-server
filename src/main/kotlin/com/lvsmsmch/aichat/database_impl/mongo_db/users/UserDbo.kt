package com.lvsmsmch.aichat.database_impl.mongo_db.users

import kotlinx.serialization.Serializable

@Serializable
data class UserDbo(
    val id: String,
    val createdAt: String,
    val email: String,
    val loginMethod: String,
    val name: String,
    val profilePicUrl: String,
)