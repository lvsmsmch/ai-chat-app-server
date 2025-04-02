package com.lvsmsmch.aichat.network.dto_objects

import kotlinx.serialization.Serializable

@Serializable
data class CharacterDetailsDto(
    val prompt: String,
    val publisher: UserDto?,
    val recentReviews: List<ReviewDto>,
)