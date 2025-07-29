package com.lvsmsmch.aichat.user.network

import com.lvsmsmch.aichat._common.database.*
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.character.database.CharacterVisibility
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import java.io.File

fun Route.configureUserRouting(
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
    followRepository: FollowRepository,
    characterRepository: CharacterRepository,
    reportRepository: ReportRepository,
    complexQueryHelper: ComplexQueryHelper,
    mapper: Mapper
) {
    route("/users") {

        /**
         * GET /users/{userId}
         * Получение основной информации о пользователе
         */
        get("/{userId}") {
            sessionRepository.verifyToken(call)

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            val userDbo = userRepository.getUserById(userId)
                ?: throw UserNotFoundException(id = userId)

            call.respondSuccess(data = userDbo.toUserDto(mapper))
        }

        /**
         * GET /users/{userId}/details
         * Получение детальной информации о пользователе
         */
        get("/{userId}/details") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            val userDbo = userRepository.getUserById(userId)
                ?: throw UserNotFoundException(id = userId)

            val userDetails = userDbo.toUserDetailsDto(mapper, demanderId = currentUserId)
            call.respondSuccess(data = userDetails)
        }

        /**
         * GET /users/{userId}/characters
         * Получение персонажей пользователя с поддержкой курсорной пагинации
         */
        get("/{userId}/characters") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            val request = GetUserCharactersRequest(
                visibility = call.request.queryParameters["visibility"]?.toIntOrNull(),
                cursor = call.request.queryParameters["cursor"],
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10,
            )

            request.visibility?.let { validateCharacterVisibility(it) }
            require(request.size in 1..100) { "Size must be between 1 and 100" }

            if (userRepository.getUserById(userId) == null) {
                throw UserNotFoundException(id = userId)
            }

            val isOwner = currentUserId == userId

            val result = characterRepository.getUserCharactersWithCursor(
                userId = userId,
                includePrivate = isOwner,
                visibility = if (isOwner) request.visibility else CharacterVisibility.PUBLIC.code,
                cursor = request.cursor,
                size = request.size
            )

            val charactersDto = result.items.map { it.toCharacterDto(mapper) }
            val response = UserCharactersResponse(
                characters = charactersDto,
                nextCursor = result.nextCursor,
                hasMore = result.hasMore
            )
            call.respondSuccess(data = response)
        }

        /**
         * GET /users/{userId}/followers
         * Получение подписчиков пользователя
         */
        get("/{userId}/followers") {
            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            val request = GetFollowersRequest(
                cursor = call.request.queryParameters["cursor"],
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10
            )

            require(request.size in 1..100) { "Size must be between 1 and 100" }

            val beforeTime = request.cursor?.let { UtcTimestamp.parse(it) }

            val followsDbos = followRepository.getFollowers(
                userId = userId,
                beforeTime = beforeTime,
                size = request.size + 1
            )

            val hasMore = followsDbos.size > request.size
            val followersToReturn = if (hasMore) followsDbos.dropLast(1) else followsDbos

            val followers = followersToReturn.mapNotNull {
                val follower = userRepository.getUserById(it.followerId) ?: return@mapNotNull null
                FollowerDto(
                    followedAt = it.followedAt.toString(),
                    follower = follower.toUserDto(mapper)
                )
            }

            val nextCursor = if (hasMore) followersToReturn.lastOrNull()?.followedAt?.toString() else null

            val response = FollowersResponse(
                followers = followers,
                nextCursor = nextCursor,
                hasMore = hasMore
            )

            call.respondSuccess(data = response)
        }

        /**
         * GET /users/{userId}/following
         * Получение подписок пользователя
         */
        get("/{userId}/following") {
            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            val request = GetFollowingRequest(
                cursor = call.request.queryParameters["cursor"],
                size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10
            )

            require(request.size in 1..100) { "Size must be between 1 and 100" }

            val beforeTime = request.cursor?.let { UtcTimestamp.parse(it) }

            val followsDbos = followRepository.getFollowings(
                userId = userId,
                beforeTime = beforeTime,
                size = request.size + 1
            )

            val hasMore = followsDbos.size > request.size
            val followingToReturn = if (hasMore) followsDbos.dropLast(1) else followsDbos

            val following = followingToReturn.mapNotNull {
                val followingUser = userRepository.getUserById(it.followeeId) ?: return@mapNotNull null
                FollowingDto(
                    followedAt = it.followedAt.toString(),
                    following = followingUser.toUserDto(mapper)
                )
            }

            val nextCursor = if (hasMore) followingToReturn.lastOrNull()?.followedAt?.toString() else null

            val response = FollowingResponse(
                following = following,
                nextCursor = nextCursor,
                hasMore = hasMore
            )

            call.respondSuccess(data = response)
        }

        /**
         * PATCH /users/{userId}
         * Обновление профиля пользователя (multipart/form-data)
         */
        patch("/{userId}") {
            val sessionDbo = sessionRepository.verifyToken(call)

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            if (userId != sessionDbo.userId) {
                throw ForbiddenException("You can only edit your own profile")
            }

            // Check content type to ensure it's multipart/form-data
            val contentType = call.request.contentType()
            if (!contentType.match(ContentType.MultiPart.FormData)) {
                throw BadRequestException("Content-Type must be multipart of form data")
            }

            // Process multipart form data
            var username: String? = null
            var name: String? = null
            var bio: String? = null
            var pictureFile: File? = null

            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        when (part.name) {
                            "username" -> username = part.value.takeIf { it.isNotBlank() }
                            "name" -> name = part.value.takeIf { it.isNotBlank() }
                            "bio" -> bio = part.value.takeIf { it.isNotBlank() }
                        }
                    }

                    is PartData.FileItem -> {
                        if (part.name == "picture") {
                            val file = File.createTempFile("profile_", ".tmp")
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
                part.dispose() // Don't forget to dispose the part after handling
            }

            if (username == null && name == null && bio == null && pictureFile == null) {
                throw NoUpdateFieldsProvidedException()
            }

            username?.let {
                validateUserUsername(it)
                if (userRepository.findByUsername(it) != null) {
                    throw UsernameAlreadyTakenException(it)
                }
            }
            name?.let { validateUserName(it) }
            bio?.let { validateUserBio(it) }
            pictureFile?.let { validateUserPicture(it) }

            val profilePictureUrl = pictureFile?.let {
                ImageServer.uploadImageOnServer(it)
            }

            complexQueryHelper.updateUser(
                userId = userId,
                username = username?.lowercase(),
                name = name,
                bio = bio,
                profilePictureUrl = profilePictureUrl,
            )

            val updatedUser = userRepository.getUserById(userId)
                ?: throw UserNotFoundException(id = userId)

            call.respondSuccess(data = updatedUser.toUserDto(mapper))
        }

        /**
         * POST /users/{userId}/follow
         * Подписка на пользователя
         */
        post("/{userId}/follow") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val targetUserId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            userRepository.getUserById(targetUserId) ?: throw throw UserNotFoundException(id = targetUserId)

            if (currentUserId == targetUserId) {
                throw BadRequestException("Cannot follow yourself")
            }

            complexQueryHelper.followUser(
                currentUserId = currentUserId,
                targetUserId = targetUserId
            )

            call.respondSuccess()
        }

        /**
         * POST /users/{userId}/unfollow
         * Отписка от пользователя
         */
        post("/{userId}/unfollow") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val targetUserId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            userRepository.getUserById(targetUserId) ?: throw throw UserNotFoundException(id = targetUserId)

            if (currentUserId == targetUserId) {
                throw BadRequestException("Cannot unfollow yourself")
            }

            complexQueryHelper.unfollowUser(
                currentUserId = currentUserId,
                targetUserId = targetUserId
            )

            call.respondSuccess()
        }

        /**
         * DELETE /users/{userId}
         * Удаление пользователя
         */
        delete("/{userId}") {
            val sessionDbo = sessionRepository.verifyToken(call)

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            if (userId != sessionDbo.userId) {
                throw BadRequestException("You can only edit your own profile")
            }

            complexQueryHelper.deleteUser(sessionDbo.userId)

            call.respondSuccess()
        }

        /**
         * POST /users/{userId}/report
         * Жалоба на пользователя
         */
        post("/{userId}/report") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            val request = call.receive<ReportUserRequest>()

            reportRepository.addReport(
                ReportDbo(
                    reportedBy = currentUserId,
                    entityType = ReportEntity.User.code,
                    entityId = userId,
                    reason = request.reason,
                    text = request.text
                )
            )

            call.respondSuccess()
        }
    }
}