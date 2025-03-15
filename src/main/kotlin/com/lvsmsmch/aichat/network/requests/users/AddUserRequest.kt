package com.lvsmsmch.aichat.network.requests.users

import java.io.File

data class AddUserRequest(
    val loginMethod: String,
    val email: String?,
    val password: String?,
    val oauthId: String?,
    val name: String,
    val profilePicture: File?,
)