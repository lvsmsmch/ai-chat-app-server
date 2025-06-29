package com.lvsmsmch.aichat.cache

import com.lvsmsmch.aichat.cache.database.*
import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.character.database.CharacterVisibility
import com.lvsmsmch.aichat.utils.UtcTimestamp

class CacheManager(
    private val characterListCopyRepository: CharacterListCopyRepository,
    private val userRecommendationsCacheRepository: UserRecommendationsCacheRepository,
    private val defaultRecommendationsCacheRepository: DefaultRecommendationsCacheRepository,
    private val categoryRecommendationsCacheRepository: CategoryRecommendationsCacheRepository,
    private val characterRepository: CharacterRepository
) {

    suspend fun getItems(
        userId: String,
        deviceId: String,
        listType: CacheListType,
        size: Int,
        cursorPosition: Int
    ): CachedCharactersResult {
        return when (listType) {
            is CacheListType.Search -> handleSearchRequest(userId, deviceId, listType, size, cursorPosition)
            else -> handleRegularRequest(userId, deviceId, listType, size, cursorPosition)
        }
    }

    // Обработка поисковых запросов
    private suspend fun handleSearchRequest(
        userId: String,
        deviceId: String,
        listType: CacheListType.Search,
        size: Int,
        cursorPosition: Int
    ): CachedCharactersResult {
        return if (cursorPosition == 0) {
            // Новый поиск - удаляем старые и создаем новый
            characterListCopyRepository.deleteAllSearchCopiesForUserDevice(userId, deviceId)
            createFreshSearchCopy(userId, deviceId, listType)
            takeItemsAndMoveCursor(userId, deviceId, listType, 0, size).copy(refreshed = true)
        } else {
            // Продолжение поиска
            takeItemsAndMoveCursor(userId, deviceId, listType, cursorPosition, size).let { result ->
                if (result.items.isEmpty() && !doesCopyExist(userId, deviceId, listType)) {
                    // Копия удалена - создаем новую
                    createFreshSearchCopy(userId, deviceId, listType)
                    takeItemsAndMoveCursor(userId, deviceId, listType, 0, size).copy(refreshed = true)
                } else {
                    result.copy(refreshed = false)
                }
            }
        }
    }

    // Обработка обычных запросов (категории, персонализация)
    private suspend fun handleRegularRequest(
        userId: String,
        deviceId: String,
        listType: CacheListType,
        size: Int,
        cursorPosition: Int
    ): CachedCharactersResult {
        return when {
            !doesCopyExist(userId, deviceId, listType) -> {
                updateCopyWithFreshList(userId, deviceId, listType)
                takeItemsAndMoveCursor(userId, deviceId, listType, 0, size).copy(refreshed = true)
            }

            else -> {
                takeItemsAndMoveCursor(userId, deviceId, listType, cursorPosition, size).copy(refreshed = false)
            }
        }
    }

    suspend fun refreshItems(
        userId: String,
        deviceId: String,
        listType: CacheListType,
        size: Int,
        moveViewedToEndIfNothingToRefresh: Boolean
    ): CachedCharactersResult {
        return when (listType) {
            is CacheListType.Search -> {
                // Для поиска рефреш = новый поиск
                characterListCopyRepository.deleteAllSearchCopiesForUserDevice(userId, deviceId)
                createFreshSearchCopy(userId, deviceId, listType)
                takeItemsAndMoveCursor(userId, deviceId, listType, 0, size).copy(refreshed = true)
            }

            else -> {
                // Категории и персонализация - как раньше
                when {
                    !doesCopyExist(userId, deviceId, listType) || doesNewerVersionExist(userId, deviceId, listType) -> {
                        updateCopyWithFreshList(userId, deviceId, listType)
                        takeItemsAndMoveCursor(userId, deviceId, listType, 0, size).copy(refreshed = true)
                    }

                    moveViewedToEndIfNothingToRefresh -> {
                        moveViewedToEnd(userId, deviceId, listType)
                        takeItemsAndMoveCursor(userId, deviceId, listType, 0, size).copy(refreshed = true)
                    }

                    else -> {
                        val copy = characterListCopyRepository.getExistingCopy(userId, deviceId, listType.code)
                        CachedCharactersResult(refreshed = false, emptyList(), copy?.currentPosition ?: 0)
                    }
                }
            }
        }
    }

    // Создание новой копии для поиска
    private suspend fun createFreshSearchCopy(
        userId: String,
        deviceId: String,
        listType: CacheListType.Search
    ) {
        val freshResults = characterRepository.getCharacters(
            searchQuery = listType.searchQuery,
            sortCriteria = listType.sortCriteria,
            page = 1,
            size = 1000, // максимум 1000 результатов
            visibilityFilter = CharacterVisibility.PUBLIC.code
        )

        val copy = CharacterListCopyDbo(
            id = "${userId}_${deviceId}_${listType.code}",
            userId = userId,
            deviceId = deviceId,
            listType = listType.code,
            characterIds = freshResults.map { it.id },
            currentPosition = 0,
            baseListVersion = "search_${System.currentTimeMillis()}",
            totalFound = freshResults.size
        )
        characterListCopyRepository.upsert(copy)
    }

    private suspend fun doesCopyExist(userId: String, deviceId: String, listType: CacheListType): Boolean {
        return characterListCopyRepository.getExistingCopy(userId, deviceId, listType.code) != null
    }

    private suspend fun takeItemsAndMoveCursor(
        userId: String,
        deviceId: String,
        listType: CacheListType,
        cursorPosition: Int,
        size: Int
    ): CachedCharactersResult {
        val copy = characterListCopyRepository.getExistingCopy(userId, deviceId, listType.code)
            ?: return CachedCharactersResult(false, emptyList(), 0)

        val validCharacters = mutableListOf<CharacterDbo>()
        var currentCursorPos = cursorPosition

        // Берем персонажей, скипая удаленных
        while (validCharacters.size < size && currentCursorPos < copy.characterIds.size) {
            copy.characterIds.getOrNull(currentCursorPos)?.let { characterId ->
                characterRepository.getCharacter(characterId)?.let { characterDbo ->
                    validCharacters.add(characterDbo)
                }
            }
            currentCursorPos++
        }

        characterListCopyRepository.updatePosition(userId, deviceId, listType.code, currentCursorPos)

        return CachedCharactersResult(false, validCharacters.toList(), currentCursorPos)
    }

    private suspend fun doesNewerVersionExist(userId: String, deviceId: String, listType: CacheListType): Boolean {
        return characterListCopyRepository.getExistingCopy(
            userId, deviceId, listType.code
        )?.baseListVersion != getCachedItemsVersion(listType, userId)
    }

    private suspend fun updateCopyWithFreshList(userId: String, deviceId: String, listType: CacheListType) {
        val cachedItems = getCachedItems(listType, userId)
        val cachedVersion = getCachedItemsVersion(listType, userId)
        if (cachedItems != null && cachedVersion != null) {
            val copy = CharacterListCopyDbo(
                id = "${userId}_${deviceId}_${listType.code}",
                userId = userId,
                deviceId = deviceId,
                listType = listType.code,
                characterIds = cachedItems,
                currentPosition = 0,
                baseListVersion = cachedVersion,
                createdAt = UtcTimestamp.now(),
                lastAccessedAt = UtcTimestamp.now()
            )
            characterListCopyRepository.upsert(copy)
        }
    }

    private suspend fun moveViewedToEnd(userId: String, deviceId: String, listType: CacheListType) {
        val copy = characterListCopyRepository.getExistingCopy(userId, deviceId, listType.code)
            ?: return

        if (copy.currentPosition <= 0) {
            return
        }

        val viewedCharacters = copy.characterIds.take(copy.currentPosition)
        val unviewedCharacters = copy.characterIds.drop(copy.currentPosition)
        val rotatedList = unviewedCharacters + viewedCharacters

        val updatedCopy = copy.copy(
            characterIds = rotatedList,
            currentPosition = 0,
            lastAccessedAt = UtcTimestamp.now()
        )

        characterListCopyRepository.upsert(updatedCopy)
    }

    private suspend fun getCachedItems(listType: CacheListType, userId: String): List<String>? {
        return when (listType) {
            is CacheListType.Personalized -> {
                userRecommendationsCacheRepository.getUserCache(userId)?.characterIds
                    ?: defaultRecommendationsCacheRepository.getDefaultCache()?.characterIds
            }

            is CacheListType.Category -> {
                categoryRecommendationsCacheRepository
                    .getCategoryCache(listType.category)?.characterIds
            }

            is CacheListType.Search -> null // поиск не использует предкэшированные списки
        }
    }

    private suspend fun getCachedItemsVersion(listType: CacheListType, userId: String): String? {
        return when (listType) {
            is CacheListType.Personalized -> {
                userRecommendationsCacheRepository.getUserCache(userId)?.version
                    ?: defaultRecommendationsCacheRepository.getDefaultCache()?.version
            }

            is CacheListType.Category -> {
                categoryRecommendationsCacheRepository
                    .getCategoryCache(listType.category)?.version
            }

            is CacheListType.Search -> null // поиск не использует версии
        }
    }
}