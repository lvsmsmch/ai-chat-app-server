package com.lvsmsmch.aichat.network.requests.characters

data class GetCharactersRequest(
    val filter: Int = 0,
    val searchQuery: String = "",
    val limit: Int = 10,
    val skip: Int = 0
)