package com.lvsmsmch.aichat.network.requests.characters

import java.io.File

data class AddCharacterRequest(
    val name: String,
    val description: String,
    val prompt: String,
    val imageFile: File?,
)