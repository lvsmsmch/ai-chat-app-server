package com.lvsmsmch.aichat.character.network

import kotlinx.serialization.Serializable

@Serializable
data class CharacterTagDto(
    val code: String // "boyfriend", "girlfriend", etc.
)