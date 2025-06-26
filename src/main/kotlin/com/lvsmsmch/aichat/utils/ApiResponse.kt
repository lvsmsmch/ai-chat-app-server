package com.lvsmsmch.aichat.utils

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val status: Int,
    val data: T? = null,
    val error: ErrorDetails? = null
)

@Serializable
data class ErrorDetails(
    val code: String,
    val message: String = ""
)