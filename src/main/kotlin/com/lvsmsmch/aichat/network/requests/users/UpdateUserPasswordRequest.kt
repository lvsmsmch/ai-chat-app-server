package com.lvsmsmch.aichat.network.requests.users

data class UpdateUserPasswordRequest(
    val userId: String,
    val password: String,
)