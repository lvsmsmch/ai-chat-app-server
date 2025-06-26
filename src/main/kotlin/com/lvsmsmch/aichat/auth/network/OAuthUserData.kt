package com.lvsmsmch.aichat.auth.network

data class OAuthUserData(
    val id: String,
    val email: String?,
    val name: String?,
    val profilePictureUrl: String?
)
