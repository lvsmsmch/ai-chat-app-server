package com.lvsmsmch.aichat.network.requests.users

data class LoginUserWithEmailRequest(
    val email: String,
    val password: String,
)
