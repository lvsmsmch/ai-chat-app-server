package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.db.repositories.content.*
import org.litote.kmongo.coroutine.CoroutineDatabase

class Repositories(
    val userRepository: UserRepository,
    val characterRepository: CharacterRepository,
    val reviewRepository: ReviewRepository,
    val chatRepository: ChatRepository,
    val messageRepository: MessageRepository,
)

fun configureRepositories(database: CoroutineDatabase): Repositories {
    return Repositories(
        userRepository = UserRepository(
            database.getCollection<UserDbo>("users")
        ),
        characterRepository = CharacterRepository(
            database.getCollection<CharacterDbo>("characters")
        ),
        reviewRepository = ReviewRepository(
            database.getCollection<CharacterDbo>("characters")
        ),

}