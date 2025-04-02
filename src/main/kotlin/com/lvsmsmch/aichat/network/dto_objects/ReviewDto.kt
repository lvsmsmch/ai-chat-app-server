package com.lvsmsmch.aichat.network.dto_objects

import kotlinx.serialization.Serializable

@Serializable
data class ReviewDto(
    val id: String,
    val publishedAt: String,
    val publisher: UserDto?,
    val rating: Int,
    val text: String
)