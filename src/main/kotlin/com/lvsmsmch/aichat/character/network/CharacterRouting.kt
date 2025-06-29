package com.lvsmsmch.aichat.character.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat._common.database.ReportDbo
import com.lvsmsmch.aichat._common.database.ReportEntity
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.cache.CacheListType
import com.lvsmsmch.aichat.cache.CacheManager
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import java.io.File

fun Route.configureCharacterRouting(
    characterRepository: CharacterRepository,
    sessionRepository: SessionRepository,
    userRepository: UserRepository,
    reportRepository: ReportRepository,
    searchSuggestionsRepository: SearchSuggestionsRepository,
    idGenerator: IdGenerator,
    cacheManager: CacheManager,
    mapper: Mapper
) {

    route("/characters") {

        /**
         * POST /characters
         * Создание нового персонажа (multipart/form-data)
         */
        post {
            val sessionDbo = sessionRepository.verifyToken(call)

            val contentType = call.request.contentType()
            if (!contentType.match(ContentType.MultiPart.FormData)) {
                throw ValidationException("Content-Type must be multipart of form data")
            }

            var name: String? = null
            var description: String? = null
            var prompt: String? = null
            var initialMessage: String? = null
            var visibility: Int? = null
            var category: String? = null
            var tags: String? = null
            var pictureFile: File? = null

            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        when (part.name) {
                            "name" -> name = part.value
                            "description" -> description = part.value
                            "prompt" -> prompt = part.value
                            "initialMessage" -> initialMessage = part.value
                            "visibility" -> visibility = part.value.toIntOrNull()
                            "category" -> category = part.value
                            "tags" -> tags = part.value
                        }
                    }

                    is PartData.FileItem -> {
                        if (part.name == "picture") {
                            val file = File.createTempFile("upload_", ".tmp")
                            part.streamProvider().use { input ->
                                file.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            pictureFile = file
                        }
                    }

                    else -> {} // Ignore other part types
                }
                part.dispose()
            }

            if (name == null) throw ValidationException("Missing name field")
            validateCharacterName(name!!)
            if (description == null) throw ValidationException("Missing description field")
            validateCharacterDescription(description!!)
            if (prompt == null) throw ValidationException("Missing prompt field")
            validateCharacterPrompt(prompt!!)
            if (initialMessage == null) throw ValidationException("Missing initialMessage field")
            validateCharacterInitialMessage(initialMessage!!)
            if (visibility == null) throw ValidationException("Missing visibility field")
            validateCharacterVisibility(visibility!!)
            if (category == null) throw ValidationException("Missing category field")
            validateCharacterCategory(category!!)
            if (tags == null) throw ValidationException("Missing tags field")
            validateCharacterTags(tags!!)
            if (pictureFile == null) throw ValidationException("Missing pictureFile field")
            validateCharacterPicture(pictureFile!!)

            val existingCharactersCount = characterRepository.getCharactersByUserId(
                userId = sessionDbo.userId, includePrivate = true
            ).size

            if (existingCharactersCount > 100) {
                throw ValidationException("Maximum characters limit exceeded (100)")
            }

            val pictureUrl = pictureFile?.let { ImageServer.uploadImageOnServer(it) } ?: ""

            val characterDbo = CharacterDbo(
                id = idGenerator.generateId(EntityType.CHARACTER),
                authorId = sessionDbo.userId,
                name = name!!,
                description = description!!,
                prompt = prompt!!,
                picUrl = pictureUrl,
                visibility = visibility!!,
                category = category!!,
                tags = tags!!.split(","),
                initialMessage = initialMessage!!,
            ).also { characterRepository.addCharacter(it) }

            call.respondSuccess(data = characterDbo.toCharacterFullInfoDto(mapper))
        }

        /**
         * GET /characters/search
         * Поиск персонажей
         */
        get("/search") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val request = SearchCharactersRequest(
                deviceId = call.request.queryParameters["deviceId"]
                    ?: throw ValidationException("Missing deviceId field"),
                searchQuery = call.request.queryParameters["searchQuery"] ?: "",
                sortCriteria = call.request.queryParameters["sortCriteria"]?.toIntOrNull() ?: 0,
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10,
                cursor = call.request.queryParameters["cursor"]?.toIntOrNull() ?: 0,
                refresh = call.request.queryParameters["refresh"]?.toBooleanStrictOrNull() ?: false
            )

            validateCharacterSearchQuery(request.searchQuery)
            validateCharacterSortCriteria(request.sortCriteria)
            require(request.size in 1..100) { "Size must be between 1 and 100" }
            require(request.cursor >= 0) { "Cursor position must be non-negative" }

            userRepository.getUserById(currentUserId) ?: throw UserNotFoundException(currentUserId)

            searchSuggestionsRepository.recordSearch(request.searchQuery)

            val searchListType = CacheListType.Search(
                searchQuery = request.searchQuery,
                sortCriteria = request.sortCriteria
            )

            val result = if (request.refresh) {
                cacheManager.refreshItems(
                    userId = currentUserId,
                    deviceId = request.deviceId,
                    listType = searchListType,
                    size = request.size,
                    moveViewedToEndIfNothingToRefresh = false
                )
            } else {
                cacheManager.getItems(
                    userId = currentUserId,
                    deviceId = request.deviceId,
                    listType = searchListType,
                    size = request.size,
                    cursorPosition = request.cursor
                )
            }

            call.respondSuccess(data = result.toDto(mapper))
        }

        /**
         * GET /characters/search/suggestions
         * Получение подсказок для поиска
         */
        get("/search/suggestions") {
            val request = GetSearchSuggestionsRequest(
                query = call.request.queryParameters["query"] ?: "",
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 5
            )

            require(request.size in 1..10) { "Size must be between 1 and 10" }

            val suggestions = searchSuggestionsRepository.getSuggestions(request.query, request.size)

            call.respondSuccess(data = SearchSuggestionsResponse(suggestions = suggestions))
        }

        /**
         * GET /characters/category/{category}
         * Получение персонажей по категории
         */
        get("/category/{category}") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val category = call.parameters["category"]
                ?: throw ValidationException("Missing category field")

            val request = GetCharactersByCategoryRequest(
                deviceId = call.request.queryParameters["deviceId"]
                    ?: throw ValidationException("Missing deviceId field"),
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10,
                cursor = call.request.queryParameters["cursor"]?.toIntOrNull() ?: 0,
                refresh = call.request.queryParameters["refresh"]?.toBooleanStrictOrNull() ?: false,
                moveViewedToEndIfNothingToRefresh = call.request.queryParameters["moveViewedToEndIfNothingToRefresh"]
                    ?.toBooleanStrictOrNull() ?: false
            )

            if (category != "personalized") {
                validateCharacterCategory(category)
            }
            require(request.size in 1..100) { "Size must be between 1 and 100" }
            require(request.cursor >= 0) { "Cursor position must be non-negative" }

            userRepository.getUserById(currentUserId) ?: throw UserNotFoundException(currentUserId)

            val listType = when (category) {
                "personalized" -> CacheListType.Personalized
                else -> CacheListType.Category(CharacterCategory.getByCode(category))
            }

            val result = if (request.refresh) {
                cacheManager.refreshItems(
                    userId = currentUserId,
                    deviceId = request.deviceId,
                    listType = listType,
                    size = request.size,
                    moveViewedToEndIfNothingToRefresh = request.moveViewedToEndIfNothingToRefresh
                )
            } else {
                cacheManager.getItems(
                    userId = currentUserId,
                    deviceId = request.deviceId,
                    listType = listType,
                    size = request.size,
                    cursorPosition = request.cursor
                )
            }

            call.respondSuccess(data = result.toDto(mapper))
        }

        /**
         * GET /characters/{id}
         * Получение основной информации о персонаже
         */
        get("/{id}") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val characterId = call.parameters["id"]
                ?: throw ValidationException("Missing characterId parameter")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (currentUserId != characterDbo.authorId && characterDbo.visibility == CharacterVisibility.PRIVATE.code) {
                throw CharacterNotFoundException(id = characterId)
            }

            call.respondSuccess(data = characterDbo.toCharacterDto(mapper))
        }

        /**
         * GET /characters/{id}/details
         * Получение детальной информации о персонаже
         */
        get("/{id}/details") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val characterId = call.parameters["id"]
                ?: throw ValidationException("Missing characterId parameter")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (currentUserId != characterDbo.authorId && characterDbo.visibility == CharacterVisibility.PRIVATE.code) {
                throw CharacterNotFoundException(id = characterId)
            }

            call.respondSuccess(data = characterDbo.toCharacterDetailsDto(mapper))
        }

        /**
         * GET /characters/{id}/private
         * Получение приватной информации о персонаже (только для автора)
         */
        get("/{id}/private") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val characterId = call.parameters["id"]
                ?: throw ValidationException("Missing characterId parameter")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (currentUserId != characterDbo.authorId) {
                throw ForbiddenException("You are not allowed to access this characters private info")
            }

            call.respondSuccess(data = characterDbo.toCharacterPrivateInfoDto(mapper))
        }

        /**
         * GET /characters/{characterId}/similar
         * Получение похожих персонажей
         */
        get("/{characterId}/similar") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val characterId = call.parameters["characterId"]
                ?: throw ValidationException("Missing characterId parameter")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            val isOwner = currentUserId == characterDbo.authorId

            if (characterDbo.visibility == CharacterVisibility.PRIVATE.code && !isOwner) {
                throw CharacterNotFoundException(id = characterId)
            }

            val similarCharacterIds = characterDbo.coOccurrenceScore
                .toList().sortedByDescending { it.second }.take(30)

            val similarCharacterDtos = similarCharacterIds.mapNotNull {
                characterRepository.getCharacter(it.first)
            }.map { it.toCharacterDto(mapper) }

            call.respondSuccess(data = SimilarCharactersResponse(characters = similarCharacterDtos))
        }

        /**
         * PATCH /characters/{characterId}
         * Обновление персонажа (multipart/form-data)
         */
        patch("/{characterId}") {
            val sessionDbo = sessionRepository.verifyToken(call)

            val characterId = call.parameters["characterId"]
                ?: throw ValidationException("Missing characterId parameter")

            val character = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (character.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to modify this character")
            }

            val contentType = call.request.contentType()
            if (!contentType.match(ContentType.MultiPart.FormData)) {
                throw ValidationException("Content-Type must be multipart of form data")
            }

            var name: String? = null
            var description: String? = null
            var prompt: String? = null
            var initialMessage: String? = null
            var visibility: Int? = null
            var category: String? = null
            var tags: String? = null
            var pictureFile: File? = null

            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        when (part.name) {
                            "name" -> name = part.value.takeIf { it.isNotBlank() }
                            "description" -> description = part.value.takeIf { it.isNotBlank() }
                            "prompt" -> prompt = part.value.takeIf { it.isNotBlank() }
                            "initialMessage" -> initialMessage = part.value.takeIf { it.isNotBlank() }
                            "visibility" -> visibility = part.value.toIntOrNull()
                            "category" -> category = part.value.takeIf { it.isNotBlank() }
                            "tags" -> tags = part.value.takeIf { it.isNotBlank() }
                        }
                    }

                    is PartData.FileItem -> {
                        if (part.name == "picture") {
                            val file = File.createTempFile("upload_", ".tmp")
                            part.streamProvider().use { input ->
                                file.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            pictureFile = file
                        }
                    }

                    else -> {} // Ignore other part types
                }
                part.dispose()
            }

            if (name == null && description == null && prompt == null && initialMessage == null &&
                visibility == null && pictureFile == null && category == null
            ) {
                throw NoUpdateFieldsProvidedException()
            }

            name?.let { validateCharacterName(it) }
            description?.let { validateCharacterDescription(it) }
            prompt?.let { validateCharacterPrompt(it) }
            initialMessage?.let { validateCharacterInitialMessage(it) }
            visibility?.let { validateCharacterVisibility(it) }
            category?.let { validateCharacterCategory(it) }
            tags?.let { validateCharacterTags(it) }
            pictureFile?.let { validateCharacterPicture(it) }

            val pictureUrl = pictureFile?.let { ImageServer.uploadImageOnServer(it) }

            characterRepository.updateCharacter(
                characterId = characterId,
                name = name,
                description = description,
                prompt = prompt,
                initialMessage = initialMessage,
                visibility = visibility,
                pictureUrl = pictureUrl,
                category = category?.let { CharacterCategory.getByCode(it) },
                tags = tags?.split(",")?.map { tagCode -> CharacterTag.getByCode(tagCode) }
            )

            val updatedCharacter = characterRepository.getCharacter(characterId)!!
            call.respondSuccess(data = updatedCharacter.toCharacterFullInfoDto(mapper))
        }

        /**
         * DELETE /characters/{characterId}
         * Удаление персонажа
         */
        delete("/{characterId}") {
            val sessionDbo = sessionRepository.verifyToken(call)

            val characterId = call.parameters["characterId"]
                ?: throw ValidationException("Missing characterId parameter")

            val character = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (character.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to modify this character")
            }

            characterRepository.deleteCharacter(characterId = characterId)
            call.respondSuccess()
        }

        /**
         * POST /characters/{characterId}/report
         * Жалоба на персонажа
         */
        post("/{characterId}/report") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val characterId = call.parameters["characterId"]
                ?: throw ValidationException("Missing characterId parameter")

            val request = call.receive<ReportCharacterRequest>()

            reportRepository.addReport(
                ReportDbo(
                    reportedBy = currentUserId,
                    entityType = ReportEntity.Character.code,
                    entityId = characterId,
                    reason = request.reason,
                    text = request.text
                )
            )

            call.respondSuccess()
        }
    }
}