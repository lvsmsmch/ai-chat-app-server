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
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Routing.configureCharacterRouting(
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

        post {
            val sessionDbo = sessionRepository.verifyToken(call)

            val contentType = call.request.contentType()
            if (!contentType.match(ContentType.MultiPart.FormData)) {
                throw BadRequestException("Content-Type must be multipart of form data")
            }

            var name: String? = null
            var description: String? = null
            var prompt: String? = null
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

            if (name == null) throw BadRequestException("Missing name field")
            validateCharacterName(name!!)
            if (description == null) throw BadRequestException("Missing description field")
            validateCharacterDescription(description!!)
            if (prompt == null) throw BadRequestException("Missing prompt field")
            validateCharacterPrompt(prompt!!)
            if (visibility == null) throw BadRequestException("Missing visibility field")
            validateCharacterVisibility(visibility!!)
            if (category == null) throw BadRequestException("Missing category field")
            validateCharacterCategory(category!!)
            if (tags == null) throw BadRequestException("Missing tags field")
            validateCharacterTags(tags!!)
            if (pictureFile == null) throw BadRequestException("Missing pictureFile field")
            validateCharacterPicture(pictureFile!!)

            val existingCharactersCount = characterRepository.getCharactersByUserId(
                userId = sessionDbo.userId, includePrivate = true
            ).size

            if (existingCharactersCount > 100) {
                throw ForbiddenException("Too much characters.")
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
                tags = tags!!.split(",")
            ).also { characterRepository.addCharacter(it) }

            call.respondSuccess(data = characterDbo.toCharacterFullInfoDto(mapper))
        }

        get("/search") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val deviceId = call.request.queryParameters["deviceId"]
                ?: throw BadRequestException("Missing deviceId field")
            val searchQuery = call.request.queryParameters["searchQuery"] ?: ""
            val sortCriteria = call.request.queryParameters["sortCriteria"]?.toIntOrNull() ?: 0
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10
            val cursor = call.request.queryParameters["cursor"]?.toIntOrNull() ?: 0
            val refresh = call.request.queryParameters["refresh"]?.toBooleanStrictOrNull() ?: false

            validateCharacterSearchQuery(searchQuery)
            validateCharacterSortCriteria(sortCriteria)
            require(size in 1..50) { "Size must be between 1 and 50" }
            require(cursor >= 0) { "Cursor position must be non-negative" }

            userRepository.getUserById(currentUserId) ?: throw UserNotFoundException(currentUserId)

            searchSuggestionsRepository.recordSearch(searchQuery)

            val searchListType = CacheListType.Search(
                searchQuery = searchQuery,
                sortCriteria = sortCriteria
            )

            val result = if (refresh) {
                cacheManager.refreshItems(
                    userId = currentUserId,
                    deviceId = deviceId,
                    listType = searchListType,
                    size = size,
                    moveViewedToEndIfNothingToRefresh = false
                )
            } else {
                cacheManager.getItems(
                    userId = currentUserId,
                    deviceId = deviceId,
                    listType = searchListType,
                    size = size,
                    cursorPosition = cursor
                )
            }

            call.respondSuccess(data = result.toDto(mapper))
        }

        get("/search/suggestions") {
            val query = call.request.queryParameters["query"] ?: ""
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 5

            require(size in 1..10) { "Size must be between 1 and 10" }

            val suggestions = searchSuggestionsRepository.getSuggestions(query, size)

            call.respondSuccess(data = suggestions)
        }

        get("/category/{category}") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val deviceId = call.request.queryParameters["deviceId"]
                ?: throw BadRequestException("Missing deviceId field")
            val category = call.parameters["category"]
                ?: throw BadRequestException("Missing category field")
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10
            val cursor = call.request.queryParameters["cursor"]?.toIntOrNull() ?: 0
            val refresh = call.request.queryParameters["refresh"]?.toBooleanStrictOrNull() ?: false
            val moveViewedToEndIfNothingToRefresh = call.request.queryParameters["moveViewedToEndIfNothingToRefresh"]
                ?.toBooleanStrictOrNull() ?: false

            if (category != "personalized") {
                validateCharacterCategory(category)
            }
            require(size > 0) { "Size must be positive" }
            require(cursor >= 0) { "Cursor position must be non-negative" }

            userRepository.getUserById(currentUserId) ?: throw UserNotFoundException(currentUserId)

            val listType = when (category) {
                "personalized" -> CacheListType.Personalized
                else -> CacheListType.Category(CharacterCategory.getByCode(category))
            }

            val result = if (refresh) {
                cacheManager.refreshItems(
                    userId = currentUserId,
                    deviceId = deviceId,
                    listType = listType,
                    size = size,
                    moveViewedToEndIfNothingToRefresh = moveViewedToEndIfNothingToRefresh
                )
            } else {
                cacheManager.getItems(
                    userId = currentUserId,
                    deviceId = deviceId,
                    listType = listType,
                    size = size,
                    cursorPosition = cursor
                )
            }

            call.respondSuccess(data = result.toDto(mapper))
        }

        get("/{id}") {
            val currentUserId = sessionRepository.verifyToken(call).id

            val characterId = call.parameters["id"]
                ?: throw BadRequestException("Missing characterId parameter")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (currentUserId != characterDbo.authorId && characterDbo.visibility == CharacterVisibility.PRIVATE.code) {
                throw CharacterNotFoundException(id = characterId)
            }

            call.respondSuccess(data = characterDbo.toCharacterDto(mapper))
        }

        get("/{id}/details") {
            val currentUserId = sessionRepository.verifyToken(call).id

            val characterId = call.parameters["id"]
                ?: throw BadRequestException("Missing characterId parameter")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (currentUserId != characterDbo.authorId && characterDbo.visibility == CharacterVisibility.PRIVATE.code) {
                throw CharacterNotFoundException(id = characterId)
            }

            call.respondSuccess(data = characterDbo.toCharacterDetailsDto(mapper))
        }

        get("/{id}/private") {
            val currentUserId = sessionRepository.verifyToken(call).id

            val characterId = call.parameters["id"]
                ?: throw BadRequestException("Missing characterId parameter")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (currentUserId != characterDbo.authorId) {
                throw ForbiddenException("You are not allowed to access this characters private info")
            }

            call.respondSuccess(data = characterDbo.toCharacterPrivateInfoDto(mapper))
        }

        get("/{characterId}/similar") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val characterId = call.parameters["characterId"]
                ?: throw BadRequestException("Missing characterId parameter")

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

            call.respondSuccess(data = similarCharacterDtos)
        }

        patch("/{characterId}") {
            val sessionDbo = sessionRepository.verifyToken(call)

            val characterId = call.parameters["characterId"]
                ?: throw BadRequestException("Missing characterId parameter")

            val character = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (character.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to modify this character")
            }

            val contentType = call.request.contentType()
            if (!contentType.match(ContentType.MultiPart.FormData)) {
                throw BadRequestException("Content-Type must be multipart of form data")
            }

            var name: String? = null
            var description: String? = null
            var prompt: String? = null
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

            if (name == null && description == null && prompt == null &&
                visibility == null && pictureFile == null && category == null
            ) {
                throw NoUpdateFieldsProvidedException()
            }

            name?.let { validateCharacterName(it) }
            description?.let { validateCharacterDescription(it) }
            prompt?.let { validateCharacterPrompt(it) }
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
                visibility = visibility,
                pictureUrl = pictureUrl,
                category = category?.let { CharacterCategory.getByCode(it) },
                tags = tags?.split(",")?.map { tagCode -> CharacterTag.getByCode(tagCode) }
            )

            val updatedCharacter = characterRepository.getCharacter(characterId)!!
            call.respondSuccess(data = updatedCharacter.toCharacterFullInfoDto(mapper))
        }

        delete("/{characterId}") {
            val sessionDbo = sessionRepository.verifyToken(call)

            val characterId = call.parameters["characterId"]
                ?: throw BadRequestException("Missing characterId parameter")

            val character = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (character.authorId != sessionDbo.userId) {
                throw ForbiddenException(errorMessage = "You are not allowed to modify this character")
            }

            characterRepository.deleteCharacter(characterId = characterId)
            call.respondSuccess()
        }

        post("/{characterId}/report") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val characterId = call.parameters["characterId"]
                ?: throw BadRequestException("Missing characterId parameter")

            val reason = call.request.queryParameters["reason"]
                ?: throw BadRequestException("Missing reason parameter")

            val text = call.request.queryParameters["text"] ?: ""

            reportRepository.addReport(
                ReportDbo(
                    reportedBy = currentUserId,
                    entityType = ReportEntity.Character.code,
                    entityId = characterId,
                    reason = reason,
                    text = text
                )
            )

            call.respondSuccess()
        }
    }
}