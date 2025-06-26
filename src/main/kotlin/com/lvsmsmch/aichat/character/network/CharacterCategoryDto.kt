package com.lvsmsmch.aichat.character.network

import kotlinx.serialization.Serializable

@Serializable
data class CharacterCategoryDto(
    val code: String,
    val color: String
)