package com.lvsmsmch.aichat.user.network

import com.lvsmsmch.aichat._common.database.ReportDbo
import com.lvsmsmch.aichat._common.database.ReportEntity
import com.lvsmsmch.aichat._common.database.ReportRepository
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.character.database.CharacterSortCriteria
import com.lvsmsmch.aichat.character.database.CharacterVisibility
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import java.io.File

fun Routing.configureUserRouting(
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
    followRepository: FollowRepository,
    characterRepository: CharacterRepository,
    reportRepository: ReportRepository,
    mapper: Mapper
) {
    route("/users") {
        
        get("/{userId}") {
            sessionRepository.verifyToken(call)

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            val userDbo = userRepository.getUserById(userId)
                ?: throw UserNotFoundException(id = userId)

            call.respondSuccess(data = userDbo.toUserDto(mapper))
        }

        get("/{userId}/details") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            val userDbo = userRepository.getUserById(userId)
                ?: throw UserNotFoundException(id = userId)

            val isFollowing = followRepository.doesConnectionExist(currentUserId, userId)
            val userDetails = userDbo.toUserDetailsDto(mapper, isDemanderFollowingThisUser = isFollowing)
            call.respondSuccess(data = userDetails)
        }

        get("/{userId}/characters") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            val visibility = call.request.queryParameters["visibility"]?.toIntOrNull()

            visibility?.let { validateCharacterVisibility(it) }

            if (userRepository.getUserById(userId) == null) {
                throw UserNotFoundException(id = userId)
            }

            val isOwner = currentUserId == userId

            val charactersDbo = characterRepository.getCharacters(
                sortCriteria = CharacterSortCriteria.NEWEST.code,
                page = 0,
                size = 1000,
                authorId = userId,
                visibilityFilter = if (isOwner) visibility else CharacterVisibility.PUBLIC.code
            )
            val charactersDto = charactersDbo.map { it.toCharacterDto(mapper) }
            call.respondSuccess(charactersDto)
        }

        get("/{userId}/followers") {
            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")
            val cursor = call.request.queryParameters["cursor"] // timestamp строка
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10

            require(size in 1..50) { "Size must be between 1 and 50" }

            val beforeTime = cursor?.let { UtcTimestamp.parse(it) }

            val followsDbos = followRepository.getFollowers(
                userId = userId,
                beforeTime = beforeTime,
                size = size + 1
            )

            val hasMore = followsDbos.size > size
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

        get("/{userId}/following") {
            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")
            val cursor = call.request.queryParameters["cursor"] // timestamp строка
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10

            require(size in 1..50) { "Size must be between 1 and 50" }

            val beforeTime = cursor?.let { UtcTimestamp.parse(it) }

            val followsDbos = followRepository.getFollowings(
                userId = userId,
                beforeTime = beforeTime,
                size = size + 1
            )

            val hasMore = followsDbos.size > size
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

        patch("/{userId}") {
            val sessionDbo = sessionRepository.verifyToken(call)

            val userId = call.parameters["userId"] ?: throw BadRequestException("Missing userId parameter")

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

            username?.let { validateUserUsername(it) }
            name?.let { validateUserName(it) }
            bio?.let { validateUserBio(it) }
            pictureFile?.let { validateUserPicture(it) }

            val profilePictureUrl = pictureFile?.let {
                ImageServer.uploadImageOnServer(it)
            }

            userRepository.updateUser(
                userId = userId,
                username = username,
                name = name,
                bio = bio,
                profilePictureUrl = profilePictureUrl,
            )

            call.respondSuccess()
        }

        post("/{userId}/follow") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            userRepository.getUserById(userId) ?: throw UserNotFoundException(userId)

            if (currentUserId == userId) {
                throw BadRequestException("Cannot follow yourself")
            }

            followRepository.addConnection(followerId = currentUserId, followeeId = userId)

            call.respondSuccess()
        }

        post("/{userId}/unfollow") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            userRepository.getUserById(userId) ?: throw UserNotFoundException(userId)

            if (currentUserId == userId) {
                throw BadRequestException("Cannot unfollow yourself")
            }

            followRepository.removeConnection(followerId = currentUserId, followeeId = userId)

            call.respondSuccess()
        }

        delete("/{userId}") {
            val sessionDbo = verifyToken(sessionRepository)

            val userId = call.parameters["userId"] ?: throw BadRequestException("Missing userId parameter")

            if (userId != sessionDbo.userId) {
                throw ForbiddenException("You can only edit your own profile")
            }

            userRepository.deleteUser(userId = userId)

            call.respondSuccess()
        }

        post("/{userId}/report") {
            val currentUserId = sessionRepository.verifyToken(call).userId

            val userId = call.parameters["userId"]
                ?: throw BadRequestException("Missing userId parameter")

            val reason = call.request.queryParameters["reason"]
                ?: throw BadRequestException("Missing reason parameter")

            val text = call.request.queryParameters["text"] ?: ""

            reportRepository.addReport(
                ReportDbo(
                    reportedBy = currentUserId,
                    entityType = ReportEntity.User.code,
                    entityId = userId,
                    reason = reason,
                    text = text
                )
            )

            call.respondSuccess()
        }
    }
}