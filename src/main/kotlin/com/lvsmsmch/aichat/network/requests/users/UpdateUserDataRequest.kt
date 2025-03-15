package com.lvsmsmch.aichat.network.requests.users

import java.io.File

data class UpdateUserDataRequest(
    val userId: String,
    val name: String?,
    val profilePicture: File?,
)