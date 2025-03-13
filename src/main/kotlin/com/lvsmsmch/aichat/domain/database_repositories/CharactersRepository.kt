package com.lvsmsmch.aichat.domain.database_repositories

import com.lvsmsmch.aichat.domain.network_dto.objects.CharacterDto
import com.lvsmsmch.aichat.domain.network_dto.requests.*

interface CharactersRepository {
    suspend fun getCharacters(getCharactersRequest: GetCharactersRequest): List<CharacterDto>
    suspend fun getCharacterById(characterId: String): CharacterDto?
    suspend fun deleteCharacter(characterId: String): Boolean
    suspend fun addCharacter(addCharacterRequest: AddCharacterRequest): Boolean
}