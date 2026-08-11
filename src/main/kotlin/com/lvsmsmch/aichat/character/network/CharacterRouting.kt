package com.lvsmsmch.aichat.character.network

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.database.*
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
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

fun Route.configureCharacterRouting(
    characterRepository: CharacterRepository,
    sessionRepository: SessionRepository,
    userRepository: UserRepository,
    reportRepository: ReportRepository,
    searchSuggestionsRepository: SearchSuggestionsRepository,
    idGenerator: IdGenerator,
    cacheManager: CacheManager,
    mapper: Mapper,
    notificationService: com.lvsmsmch.aichat.notification.NotificationService,
    discoverSectionsRepository: com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheRepository,
    characterLikeRepository: com.lvsmsmch.aichat.character.database.CharacterLikeRepository,
    characterService: com.lvsmsmch.aichat.character.CharacterService,
    chatRepository: com.lvsmsmch.aichat.chat.database.ChatRepository,
    userBlockRepository: com.lvsmsmch.aichat.user.database.UserBlockRepository,
    avatarGenerationLimitRepository: AvatarGenerationLimitRepository,
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
            var initialMessage: String? = null
            var visibility: Int? = null
            var category: String? = null
            var tags: String? = null
            var pictureFile: File? = null
            var cover: String? = null
            var coverFile: File? = null

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
                            "cover" -> cover = part.value
                        }
                    }

                    is PartData.FileItem -> {
                        if (part.name == "coverImage") {
                            val file = File.createTempFile("cover_", ".tmp")
                            part.streamProvider().use { input ->
                                file.outputStream().buffered().use { output -> input.copyTo(output) }
                            }
                            coverFile = file
                        }
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

                    else -> {}
                }
                part.dispose()
            }

            if (name == null) throw BadRequestException("Missing name field")
            validateCharacterName(name!!)
            if (description == null) throw BadRequestException("Missing description field")
            validateCharacterDescription(description!!)
            if (prompt == null) throw BadRequestException("Missing prompt field")
            validateCharacterPrompt(prompt!!)
            if (initialMessage == null) throw BadRequestException("Missing initialMessage field")
            validateCharacterInitialMessage(initialMessage!!)
            if (visibility == null) throw BadRequestException("Missing visibility field")
            validateCharacterVisibility(visibility!!)
            if (category == null) throw BadRequestException("Missing category field")
            validateCharacterCategory(category!!)
            if (tags == null) throw BadRequestException("Missing tags field")
            validateCharacterTags(tags!!)

            pictureFile?.let { validateCharacterPicture(it) }
            coverFile?.let { validateCoverPicture(it) }
            cover?.let { validateChatCover(it) }

            val existingCharactersCount = characterRepository.getCharactersByUserId(
                userId = sessionDbo.userId, includePrivate = true
            ).size

            if (existingCharactersCount > 500) {
                throw BadRequestException("Maximum characters limit exceeded (500)")
            }

            val images = pictureFile?.let { ImageServer.uploadImageOnServer(it) }
            // Своя обложка перевешивает выбранную встроенную
            val coverImages = coverFile?.let { ImageServer.uploadImageOnServer(it) }

            val userId = sessionDbo.userId

            val characterId = idGenerator.generateId(EntityType.CHARACTER)
            val characterDbo = CharacterDbo(
                id = characterId,
                authorId = userId,
                name = name!!,
                description = collapseExcessLineBreaks(description!!),
                prompt = prompt!!,
                picUrl = images?.originalUrl,
                picUrlThumbnail = images?.thumbnailUrl,
                visibility = visibility!!,
                category = category!!,
                tags = CharacterTag.fromString(tags!!).map { it.code },
                initialMessage = initialMessage!!,
                // Ничего не выбрали — обложка назначается сама, чтобы у чата
                // не было пустого фона
                cover = coverImages?.originalUrl
                    ?: cover?.takeIf { it.isNotBlank() }
                    ?: com.lvsmsmch.aichat.character.ChatCovers.defaultFor(characterId),
            )

            characterService.addCharacter(characterDbo)
            // Фолловерам автора — «добавил персонажа» (только публичные)
            if (characterDbo.visibility != CharacterVisibility.PRIVATE.code) {
                notificationService.onFolloweeNewCharacter(characterDbo.authorId, characterDbo.id)
            }

            call.respondSuccess(data = characterDbo.toCharacterFullInfoDto(mapper, sessionDbo.userId))
        }

        /**
         * Секции вкладки All: по 4 персонажа в каждой (три главные + категории),
         * все уникальны в пределах экрана. Кэш юзера или дефолт новорега.
         */
        // Публичная версия для экрана логина: дефолтный набор нового юзера,
        // язык en, без авторизации; лайков нет — юзера ещё нет
        get("/discover-sections/public") {
            val cache = discoverSectionsRepository.getForUserOrDefault("")
                ?: return@get call.respondSuccess(DiscoverSectionsResponse(emptyList()))
            val sectionsRaw = cache.sections
                .filter { it.key.startsWith(com.lvsmsmch.aichat.utils.updaters.ALL_PREFIX) }
                // for_you в дефолтном наборе нет, но на всякий случай фильтруем
                .filterNot { it.key.contains("for_you") }
            // Персонажи ВСЕХ секций разом: раньше на каждого шёл свой запрос,
            // то есть тринадцать секций по тридцать давали под четыреста
            val byId = mapper
                .charactersDtoByIds(sectionsRaw.flatMap { it.characterIds }.distinct(), "en", emptySet())
                .associateBy { it.id }
            val sections = sectionsRaw.map { s ->
                DiscoverSectionDto(
                    key = s.key.removePrefix(com.lvsmsmch.aichat.utils.updaters.ALL_PREFIX),
                    characters = s.characterIds.mapNotNull { byId[it] },
                    total = s.characterIds.size,
                )
            }
            call.respondSuccess(DiscoverSectionsResponse(sections))
        }

        get("/discover-sections") {
            val currentUserId = sessionRepository.verifyToken(call).userId
            val lang = mapper.languageOf(currentUserId)
            val cache = discoverSectionsRepository.getForUserOrDefault(currentUserId)
                ?: return@get call.respondSuccess(DiscoverSectionsResponse(emptyList()))
            val sectionLiked = characterLikeRepository.getLikedIds(
                currentUserId,
                cache.sections.flatMap { it.characterIds },
            )
            val sectionsRaw = cache.sections
                .filter { it.key.startsWith(com.lvsmsmch.aichat.utils.updaters.ALL_PREFIX) }
            // Заблокированные авторы: кэш секций общий и о блокировках не знает,
            // поэтому чистим на выдаче
            val blocked = userBlockRepository.getBlockedIds(currentUserId)
            val byId = mapper.charactersDtoByIds(
                sectionsRaw.flatMap { it.characterIds }.distinct(), lang, sectionLiked,
                blockedAuthorIds = blocked,
            ).associateBy { it.id }
            val sections = sectionsRaw.map { s ->
                val characters = s.characterIds.mapNotNull { byId[it] }
                DiscoverSectionDto(
                    key = s.key.removePrefix(com.lvsmsmch.aichat.utils.updaters.ALL_PREFIX),
                    characters = characters,
                    // Счётчик — по тому, что реально осталось видно: иначе
                    // «23 characters» обещало бы больше, чем откроется
                    total = if (blocked.isEmpty()) s.characterIds.size else characters.size,
                )
            }
            call.respondSuccess(DiscoverSectionsResponse(sections))
        }

        get("/search") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val request = SearchCharactersRequest(
                deviceId = call.request.queryParameters["deviceId"]
                    ?: throw BadRequestException("Missing deviceId field"),
                searchQuery = call.request.queryParameters["searchQuery"] ?: "",
                sortCriteria = call.request.queryParameters["sortCriteria"]?.toIntOrNull() ?: 0,
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10,
                cursor = call.request.queryParameters["cursor"]
            )

            validateCharacterSearchQuery(request.searchQuery)
            validateCharacterSortCriteria(request.sortCriteria)
            require(request.size in 1..100) { "Size must be between 1 and 100" }

            val cursor = try {
                request.cursor?.toInt() ?: 0
            } catch (e: Exception) {
                throw BadRequestException("Cursor must be an int in a string format")
            }
            require(cursor >= 0) { "Cursor position must be non-negative" }

            userRepository.getUserById(currentUserId) ?: throw UserNotFoundException(currentUserId)

            if (request.searchQuery.isNotBlank()) {
                searchSuggestionsRepository.recordSearch(request.searchQuery)
            }

            val searchListType = CacheListType.Search(
                searchQuery = request.searchQuery,
                sortCriteria = request.sortCriteria
            )

            val result = cacheManager.getItems(
                userId = currentUserId,
                deviceId = request.deviceId,
                listType = searchListType,
                size = request.size,
                cursorPosition = cursor
            )

            call.respondSuccess(
                data = result.toDto(
                    mapper, mapper.languageOf(currentUserId), likerId = currentUserId,
                    blockedAuthorIds = userBlockRepository.getBlockedIds(currentUserId),
                )
            )
        }

        /**
         * Список для пикера группового чата — уже отсортированный по
         * релевантности, без фильтров на клиенте.
         *
         * Порядок: сначала те, с кем юзер реально переписывался (свежие чаты
         * сверху), затем сохранённые, затем популярные. Считается на сервере,
         * потому что клиент видит только свою страницу списка и «часто
         * взаимодействует» на ней не вычислит: нужны все чаты и все лайки
         * разом.
         *
         * Пагинация страничная: курсор — номер страницы, как у ленты.
         */
        get("/picker") {
            val userId = sessionRepository.verifyToken(call).userId
            val pageSize = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
            val page = call.request.queryParameters["cursor"]?.toIntOrNull() ?: 1
            require(pageSize in 1..100) { "Size must be between 1 and 100" }
            require(page >= 1) { "Cursor must be a positive page number" }

            // Ранжирование строим ЦЕЛИКОМ, затем режем на страницы: порядок
            // должен быть устойчивым между запросами, иначе на второй странице
            // всплывут те же персонажи, что были на первой
            val chats = chatRepository.getChatsByUserId(userId)
            val chatted = chats.flatMap { it.characterIds }.distinct()
            val (likedIds, _) = characterLikeRepository.getLikedCharacterIds(userId, null, 200)
            val liked = likedIds.filterNot { it in chatted }

            val ranked = chatted + liked
            val needed = page * pageSize

            // Хвост добираем популярными, пропуская уже попавших выше
            val popular = if (ranked.size < needed) {
                characterRepository.getCharacters(
                    sortCriteria = CharacterSortCriteria.MOST_POPULAR.code,
                    page = 1,
                    size = needed + ranked.size,
                ).map { it.id }.filterNot { it in ranked.toSet() }
            } else emptyList()

            val pageIds = (ranked + popular).drop((page - 1) * pageSize).take(pageSize)
            val lang = mapper.languageOf(userId)
            val pageLiked = characterLikeRepository.getLikedIds(userId, pageIds)
            val characters = mapper
                .charactersDtoByIds(
                    pageIds, lang, pageLiked, publicOnly = false,
                    blockedAuthorIds = userBlockRepository.getBlockedIds(userId),
                )
                // Приватные чужие сюда попасть не должны: свои — можно, с ними
                // юзер и переписывается
                .filter { it.visibility == CharacterVisibility.PUBLIC.code || it.author.id == userId }

            call.respondSuccess(
                data = CachedCharactersResultDto(
                    items = characters,
                    // Конец списка определяем по СТРАНИЦЕ id, а не по тому, что
                    // осталось после фильтров: иначе одна заблокированная плитка
                    // обрывала бы пагинацию на середине
                    nextCursor = if (pageIds.size < pageSize) null else (page + 1).toString(),
                )
            )
        }

        get("/search/suggestions") {
            val request = GetSearchSuggestionsRequest(
                query = call.request.queryParameters["query"] ?: "",
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 5
            )

            require(request.size in 1..10) { "Size must be between 1 and 10" }

            val suggestions = searchSuggestionsRepository.getSuggestions(request.query, request.size)

            call.respondSuccess(data = SearchSuggestionsResponse(suggestions = suggestions))
        }

        rateLimit(RateLimitName("ai-search")) {
            post("/search/personality") {
                val currentUserId = sessionRepository.verifyToken(call).userId
                val query = call.receive<PersonalitySearchRequest>().query.trim()
                require(query.length in 3..500) {
                    "Personality description must be between 3 and 500 characters"
                }

                val blockedAuthors = userBlockRepository.getBlockedIds(currentUserId)
                val language = mapper.languageOf(currentUserId)
                val candidates = characterRepository.getAllPublicCharacters()
                    .filterNot { it.authorId in blockedAuthors }
                    .map { it.localized(language) }
                val ids = PersonalitySearchUtil.findCharacterIds(query, candidates)
                val likedIds = characterLikeRepository.getLikedIds(currentUserId, ids)
                val characters = mapper.charactersDtoByIds(
                    ids = ids,
                    lang = language,
                    likedIds = likedIds,
                    publicOnly = true,
                    blockedAuthorIds = blockedAuthors,
                )

                call.respondSuccess(PersonalitySearchResponse(characters))
            }
        }

        get("/avatar-generation/limits") {
            val userId = sessionRepository.verifyToken(call).userId
            val quota = avatarGenerationLimitRepository.current(userId)
            call.respondSuccess(quota.toAvatarLimitsDto())
        }

        get("/avatar-generation/{requestId}") {
            val userId = sessionRepository.verifyToken(call).userId
            val requestId = call.parameters["requestId"].orEmpty()
            require(requestId.length in 8..100) { "Invalid avatar generation request id" }
            val result = avatarGenerationLimitRepository.result(userId, requestId)
            val quota = avatarGenerationLimitRepository.current(userId)
            call.respondSuccess(
                GenerateAvatarResponse(
                    requestId = requestId,
                    generationStatus = result?.status ?: "failed",
                    imageUrl = result?.imageUrl,
                    limits = quota.toAvatarLimitsDto(),
                )
            )
        }

        post("/avatar-generation/generate") {
            val userId = sessionRepository.verifyToken(call).userId
            val request = call.receive<GenerateAvatarRequest>()
            val description = request.description.trim()
            // Старые клиенты не присылают id: для них сохраняем прежнее
            // поведение, а новые получают идемпотентное восстановление.
            val requestId = call.request.headers["X-Avatar-Request-Id"]
                ?: UUID.randomUUID().toString()
            require(description.length in 3..500) {
                "Avatar description must be between 3 and 500 characters"
            }
            require(requestId.length in 8..100) {
                "Invalid avatar generation request id"
            }

            val attempt = avatarGenerationLimitRepository.reserve(userId, requestId)
            if (attempt.status != "pending" || !attempt.shouldGenerate) {
                return@post call.respondSuccess(
                    GenerateAvatarResponse(
                        requestId = attempt.requestId,
                        generationStatus = attempt.status,
                        imageUrl = attempt.imageUrl,
                        limits = attempt.quota.toAvatarLimitsDto(),
                        limitReason = attempt.quota.limitReason,
                    )
                )
            }

            try {
                // Отвязано от жизни клиентского сокета: если приложение уйдёт в
                // фон и соединение оборвётся, генерация завершится и сохранит URL.
                val result = withContext(NonCancellable) {
                    try {
                        com.lvsmsmch.aichat.chat.network.AiImageGeneratorUtil
                            .generateAvatar(description)
                            .also {
                                avatarGenerationLimitRepository.complete(
                                    userId = userId,
                                    requestId = attempt.requestId,
                                    imageUrl = it.url,
                                )
                            }
                    } catch (e: Exception) {
                        avatarGenerationLimitRepository.fail(userId, attempt.requestId, attempt.quota)
                        throw e
                    }
                }
                call.respondSuccess(
                    GenerateAvatarResponse(
                        requestId = attempt.requestId,
                        generationStatus = "completed",
                        imageUrl = result.url,
                        limits = attempt.quota.toAvatarLimitsDto(),
                    )
                )
            } catch (e: Exception) {
                throw e
            }
        }

        get("/category/{category}") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val category = call.parameters["category"]
                ?: throw BadRequestException("Missing category field")

            val request = GetCharactersByCategoryRequest(
                deviceId = call.request.queryParameters["deviceId"]
                    ?: throw BadRequestException("Missing deviceId field"),
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10,
                cursor = call.request.queryParameters["cursor"],
                refresh = call.request.queryParameters["refresh"]?.toBooleanStrictOrNull() ?: false,
            )

            val mainKeys = setOf(
                com.lvsmsmch.aichat.utils.updaters.KEY_TRENDING,
                com.lvsmsmch.aichat.utils.updaters.KEY_MOST_POPULAR,
            )
            if (category != "personalized" && category !in mainKeys) {
                validateCharacterCategory(category)
            }
            require(request.size in 1..100) { "Size must be between 1 and 100" }

            val cursor = try {
                request.cursor?.toInt() ?: 0
            } catch (e: Exception) {
                throw BadRequestException("Cursor must be an int in a string format")
            }

            require(cursor >= 0) { "Cursor position must be non-negative" }

            // Вкладки Trending / Most popular — из кэша Discover (200 персонажей),
            // курсор = offset; пагинация та же, что у категорий
            if (category in mainKeys) {
                val lang = mapper.languageOf(currentUserId)
                val ids = discoverSectionsRepository.getForUserOrDefault(currentUserId)
                    ?.sections?.firstOrNull { it.key == category }?.characterIds.orEmpty()
                val page = ids.drop(cursor).take(request.size)
                val likedIds = characterLikeRepository.getLikedIds(currentUserId, page)
                val characters = mapper.charactersDtoByIds(
                    page, lang, likedIds,
                    blockedAuthorIds = userBlockRepository.getBlockedIds(currentUserId),
                )
                val next = (cursor + page.size).takeIf { it < ids.size }?.toString()
                return@get call.respondSuccess(
                    CachedCharactersResultDto(
                        refreshed = request.refresh,
                        items = characters,
                        nextCursor = next,
                    )
                )
            }

            userRepository.getUserById(currentUserId) ?: throw UserNotFoundException(currentUserId)

            val listType = when (category) {
                "personalized" -> CacheListType.Personalized
                else -> CacheListType.Category(CharacterCategory.getByCode(category))
            }

            val result = if (request.refresh) {
                cacheManager.refreshCategory(
                    userId = currentUserId,
                    deviceId = request.deviceId,
                    listType = listType,
                    size = request.size
                )
            } else {
                cacheManager.getItems(
                    userId = currentUserId,
                    deviceId = request.deviceId,
                    listType = listType,
                    size = request.size,
                    cursorPosition = cursor
                )
            }

            call.respondSuccess(
                data = result.toDto(
                    mapper, mapper.languageOf(currentUserId), likerId = currentUserId,
                    blockedAuthorIds = userBlockRepository.getBlockedIds(currentUserId),
                )
            )
        }

        get("/{id}") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val characterId = call.parameters["id"]
                ?: throw BadRequestException("Missing characterId parameter")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (currentUserId != characterDbo.authorId && characterDbo.visibility == CharacterVisibility.PRIVATE.code) {
                throw CharacterNotFoundException(id = characterId)
            }

            call.respondSuccess(
                data = characterDbo.toCharacterDto(
                    mapper, mapper.languageOf(currentUserId), likerId = currentUserId,
                )
            )
        }

        get("/{id}/details") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val characterId = call.parameters["id"]
                ?: throw BadRequestException("Missing characterId parameter")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (currentUserId != characterDbo.authorId && characterDbo.visibility == CharacterVisibility.PRIVATE.code) {
                throw CharacterNotFoundException(id = characterId)
            }

            call.respondSuccess(data = characterDbo.toCharacterDetailsDto(mapper, currentUserId))
        }

        get("/{id}/private") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val characterId = call.parameters["id"]
                ?: throw BadRequestException("Missing characterId parameter")

            val characterDbo = characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)

            if (currentUserId != characterDbo.authorId) {
                throw ForbiddenException("You are not allowed to access this characters private info")
            }

            call.respondSuccess(data = characterDbo.toCharacterPrivateInfoDto(mapper, mapper.languageOf(currentUserId)))
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

            // Контентный список (франшиза/теги/категория, есть у всех) —
            // приоритетно; поведенческий coOccurrence — фолбэк, пока апдейтер
            // не успел посчитать новые персонажи
            val similarCharacterIds = characterDbo.similarCharacterIds.ifEmpty {
                characterDbo.coOccurrenceScore
                    .toList().sortedByDescending { it.second }.take(30).map { it.first }
            }

            val similarLiked = characterLikeRepository.getLikedIds(currentUserId, similarCharacterIds)
            val similarCharacterDtos = mapper.charactersDtoByIds(
                ids = similarCharacterIds,
                lang = mapper.languageOf(currentUserId),
                likedIds = similarLiked,
                publicOnly = true,
                blockedAuthorIds = userBlockRepository.getBlockedIds(currentUserId),
            )

            call.respondSuccess(data = SimilarCharactersResponse(characters = similarCharacterDtos))
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
            var initialMessage: String? = null
            var visibility: Int? = null
            var category: String? = null
            var tags: String? = null
            var removePicture: Boolean? = false
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
                            "removePicture" -> removePicture = part.value.toBoolean()
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

                    else -> {}
                }
                part.dispose()
            }


            name?.let { validateCharacterName(it) }
            description?.let { validateCharacterDescription(it) }
            prompt?.let { validateCharacterPrompt(it) }
            initialMessage?.let { validateCharacterInitialMessage(it) }
            visibility?.let { validateCharacterVisibility(it) }
            category?.let { validateCharacterCategory(it) }
            tags?.let { validateCharacterTags(it) }
            pictureFile?.let { validateCharacterPicture(it) }

            val images = pictureFile?.let { ImageServer.uploadImageOnServer(it) }

            val updatedCharacter = characterService.updateCharacter(
                characterId = characterId,
                userId = character.authorId,
                name = name,
                description = description?.let { collapseExcessLineBreaks(it) },
                prompt = prompt,
                initialMessage = initialMessage,
                visibility = visibility,
                pictureUrl = images?.originalUrl,
                pictureUrlThumbnail = images?.thumbnailUrl,
                removePicture = removePicture,
                category = category,
                tags = tags,
                oldName = character.name,
                oldVisibility = character.visibility,
            )

            call.respondSuccess(data = updatedCharacter.toCharacterFullInfoDto(mapper, sessionDbo.userId))
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

            characterService.deleteCharacter(characterId)

            call.respondSuccess()
        }

        /** Лайк персонажа (идемпотентно: повтор не накручивает счётчик). */
        post("/{characterId}/like") {
            val userId = sessionRepository.verifyToken(call).userId
            val characterId = call.parameters["characterId"]
                ?: throw BadRequestException("Missing characterId parameter")
            characterRepository.getCharacter(characterId)
                ?: throw CharacterNotFoundException(id = characterId)
            characterService.like(characterId, userId)
            call.respondSuccess(com.lvsmsmch.aichat.chat.network.IsSuccessResponse(isSuccess = true))
        }

        post("/{characterId}/unlike") {
            val userId = sessionRepository.verifyToken(call).userId
            val characterId = call.parameters["characterId"]
                ?: throw BadRequestException("Missing characterId parameter")
            characterService.unlike(characterId, userId)
            call.respondSuccess(com.lvsmsmch.aichat.chat.network.IsSuccessResponse(isSuccess = true))
        }

        post("/{characterId}/report") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val characterId = call.parameters["characterId"]
                ?: throw BadRequestException("Missing characterId parameter")

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

private fun AvatarGenerationQuota.toAvatarLimitsDto() = AvatarGenerationLimitsDto(
    hourlyUsed = hourlyUsed,
    hourlyLimit = hourlyLimit,
    dailyUsed = dailyUsed,
    dailyLimit = dailyLimit,
    hourlyResetAfterSeconds = hourlyResetAfterSeconds,
    dailyResetAfterSeconds = dailyResetAfterSeconds,
)
