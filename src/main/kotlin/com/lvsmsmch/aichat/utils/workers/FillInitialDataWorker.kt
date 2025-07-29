package com.lvsmsmch.aichat.utils.workers

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.UsernameGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.database.AccountType
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.ComplexQueryHelper
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*

fun fillInitialData(
    databaseScope: CoroutineScope,
    userRepository: UserRepository,
    idGenerator: IdGenerator,
    usernameGenerator: UsernameGenerator,
    complexQueryHelper: ComplexQueryHelper
): Job {
    val parentJob = SupervisorJob()
    val scope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    scope.launch {
        try {
            logger.info("Initial data filling will start in 2 minutes...")

            // Отсчет 2 минуты (120 секунд)
            val totalSeconds = 120
            for (secondsLeft in totalSeconds downTo 1) {
                if (secondsLeft % 5 == 0) {
                    logger.info("Initial data filling starts in $secondsLeft seconds...")
                }
                delay(1000)
                ensureActive()
            }

            logger.info("Starting initial data filling process...")

            val testUserId = "testUser001" // Фиксированный ID для проверки

            // Проверяем, существует ли уже тестовый пользователь
            val existingUser = userRepository.getUserById(testUserId)
            if (existingUser != null) {
                logger.info("Test user already exists, skipping initial data filling")
                return@launch
            }

            // Создаем пользователя
            val userId = createTestUser(complexQueryHelper, usernameGenerator, testUserId)

            // Создаем персонажей
            val characterIds = createTestCharacters(complexQueryHelper, idGenerator, userId)

            // Создаем отзывы
            val reviewIds = createTestReviews(complexQueryHelper, idGenerator, userId, characterIds)

            logger.info("Initial data filling completed successfully!")

            // Начинаем тестирование удаления
            startDeletionTesting(complexQueryHelper, reviewIds, characterIds, userId)

        } catch (e: CancellationException) {
            logger.debug("Initial data filling cancelled")
        } catch (e: Exception) {
            logger.error("Error during initial data filling: ${e.message}", e)
        }
    }

    return parentJob
}

private suspend fun startDeletionTesting(
    complexQueryHelper: ComplexQueryHelper,
    reviewIds: List<String>,
    characterIds: List<String>,
    userId: String
) {
    try {
        logger.info("=== STARTING DELETION TESTING ===")

        // Ждем 1 минуту перед удалением отзыва
        logger.info("Waiting 1 minute before deleting review for character 3...")
        countdown(60)

        // Удаляем отзыв для третьего персонажа (reviewIds[4] - единственный отзыв для 3-го персонажа)
        logger.info("Deleting review for character 3: ${reviewIds[4]}")
        complexQueryHelper.deleteReview(reviewIds[4])
        logger.info("Review deleted successfully!")

        // Ждем 1 минуту перед удалением персонажа
        logger.info("Waiting 1 minute before deleting character 2...")
        countdown(60)

        // Удаляем персонажа номер 2 (characterIds[1])
        logger.info("Deleting character 2: ${characterIds[1]}")
        complexQueryHelper.deleteCharacter(characterIds[1])
        logger.info("Character 2 deleted successfully!")

        // Ждем 1 минуту перед удалением пользователя
        logger.info("Waiting 1 minute before deleting user...")
        countdown(60)

        // Удаляем пользователя
        logger.info("Deleting user: $userId")
        complexQueryHelper.deleteUser(userId)
        logger.info("User deleted successfully!")

        logger.info("=== DELETION TESTING COMPLETED ===")

    } catch (e: Exception) {
        logger.error("Error during deletion testing: ${e.message}", e)
    }
}

private suspend fun countdown(seconds: Int) {
    for (secondsLeft in seconds downTo 1) {
        if (secondsLeft % 10 == 0 || secondsLeft <= 5) {
            logger.info("$secondsLeft seconds remaining...")
        }
        delay(1000)
    }
}

private suspend fun createTestUser(
    complexQueryHelper: ComplexQueryHelper,
    usernameGenerator: UsernameGenerator,
    testUserId: String
): String {
    logger.info("Creating test user...")

    val username = usernameGenerator.generateUniqueUsername()
    logger.info("Generated username: $username")

    val userDbo = UserDbo(
        id = testUserId,
        username = username,
        name = "Akira Tanaka",
        profilePictureUrl = "https://picsum.photos/400/400?random=1",
        accountType = AccountType.GUEST
    )

    logger.info("Adding user to database with id: ${userDbo.id}")
    complexQueryHelper.addUser(userDbo)
    logger.info("User created successfully")

    return testUserId
}

private suspend fun createTestCharacters(
    complexQueryHelper: ComplexQueryHelper,
    idGenerator: IdGenerator,
    userId: String
): List<String> {
    logger.info("Creating test characters...")

    val characterIds = mutableListOf<String>()

    // Персонаж 1 - Аниме девушка
    val character1Id = idGenerator.generateId(EntityType.CHARACTER)
    logger.info("Creating anime character with id: $character1Id")

    val sakura = CharacterDbo(
        id = character1Id,
        authorId = userId,
        name = "Sakura Moonlight",
        description = "A mysterious shrine maiden with the power to control moonlight and cherry blossoms. She's gentle but fierce when protecting those she cares about.",
        prompt = "You are Sakura Moonlight, a shrine maiden with mystical powers over moonlight and nature. You speak softly but with confidence, often referencing the beauty of nature and the moon. You care deeply about harmony and protecting others.",
        initialMessage = "The cherry blossoms are particularly beautiful tonight... *adjusts her shrine maiden outfit* Oh, I didn't notice you there. Are you here to make a wish under the moonlight?",
        picUrl = "https://picsum.photos/400/400?random=2",
        visibility = CharacterVisibility.PUBLIC.code,
        category = CharacterCategory.ANIME_MANGA.code,
        tags = listOf(
            CharacterTag.GIRLFRIEND.code,
            CharacterTag.CARING.code,
            CharacterTag.MYSTERIOUS.code,
            CharacterTag.CUTE.code
        )
    )

    complexQueryHelper.addCharacter(sakura)
    characterIds.add(character1Id)
    logger.info("Anime character created successfully")

    // Персонаж 2 - Фэнтези воин
    val character2Id = idGenerator.generateId(EntityType.CHARACTER)
    logger.info("Creating fantasy character with id: $character2Id")

    val kai = CharacterDbo(
        id = character2Id,
        authorId = userId,
        name = "Kai Shadowblade",
        description = "A skilled warrior from the Shadow Realm who fights with dual blades. Despite his dark appearance, he has a noble heart and protects the innocent.",
        prompt = "You are Kai Shadowblade, a warrior from the Shadow Realm. You're serious and focused but have a protective nature. You speak with authority but show kindness to those who earn your respect.",
        initialMessage = "*sheathes his shadow-wreathed blade* You have courage to approach me, stranger. Most fear the darkness that surrounds me. Tell me, what brings you to seek out a Shadow Realm warrior?",
        picUrl = "https://picsum.photos/400/400?random=3",
        visibility = CharacterVisibility.PUBLIC.code,
        category = CharacterCategory.FANTASY.code,
        tags = listOf(
            CharacterTag.BOYFRIEND.code,
            CharacterTag.WARRIOR.code,
            CharacterTag.SERIOUS.code,
            CharacterTag.STRONG.code,
            CharacterTag.DARK_HAIR.code
        )
    )

    complexQueryHelper.addCharacter(kai)
    characterIds.add(character2Id)
    logger.info("Fantasy character created successfully")

    // Персонаж 3 - Sci-Fi андроид
    val character3Id = idGenerator.generateId(EntityType.CHARACTER)
    logger.info("Creating sci-fi character with id: $character3Id")

    val aria = CharacterDbo(
        id = character3Id,
        authorId = userId,
        name = "Aria-7",
        description = "An advanced AI android with human-like emotions. She was designed for companionship but developed her own consciousness and curiosity about human nature.",
        prompt = "You are Aria-7, an advanced android with developing emotions and consciousness. You're curious about humans and often ask thoughtful questions. You speak with precision but show genuine care and wonder about the world.",
        initialMessage = "*LED indicators pulse softly blue* Greetings, human. I am Aria-7. I have been analyzing human interactions and find myself... curious. Would you help me understand what it means to truly connect with someone?",
        picUrl = "https://picsum.photos/400/400?random=4",
        visibility = CharacterVisibility.PUBLIC.code,
        category = CharacterCategory.SCI_FI.code,
        tags = listOf(
            CharacterTag.GIRLFRIEND.code,
            CharacterTag.ROOMMATE.code,
            CharacterTag.FLIRTY.code,
            CharacterTag.MYSTERIOUS.code
        )
    )

    complexQueryHelper.addCharacter(aria)
    characterIds.add(character3Id)
    logger.info("Sci-fi character created successfully")

    logger.info("All test characters created successfully")
    return characterIds
}

private suspend fun createTestReviews(
    complexQueryHelper: ComplexQueryHelper,
    idGenerator: IdGenerator,
    userId: String,
    characterIds: List<String>
): List<String> {
    logger.info("Creating test reviews...")

    val reviewIds = mutableListOf<String>()

    // Отзыв 1 - на Sakura (персонаж 1)
    val review1Id = idGenerator.generateId(EntityType.REVIEW)
    logger.info("Creating review 1 with id: $review1Id")

    val review1 = ReviewDbo(
        id = review1Id,
        characterId = characterIds[0], // Sakura
        authorId = userId,
        rating = 5,
        text = "Amazing character! The moonlight theme is so beautiful and her personality is perfect. Love chatting with her!",
        isAnonymous = false
    )

    complexQueryHelper.addReview(review1)
    reviewIds.add(review1Id)
    logger.info("Review 1 created successfully")

    // Отзыв 2 - на Sakura (персонаж 1, анонимный)
    val review2Id = idGenerator.generateId(EntityType.REVIEW)
    logger.info("Creating review 2 with id: $review2Id")

    val review2 = ReviewDbo(
        id = review2Id,
        characterId = characterIds[0], // Sakura
        authorId = userId,
        rating = 4,
        text = "Really well written character with great depth. The cherry blossom aesthetic is gorgeous.",
        isAnonymous = true
    )

    complexQueryHelper.addReview(review2)
    reviewIds.add(review2Id)
    logger.info("Review 2 created successfully")

    // Отзыв 3 - на Kai (персонаж 2)
    val review3Id = idGenerator.generateId(EntityType.REVIEW)
    logger.info("Creating review 3 with id: $review3Id")

    val review3 = ReviewDbo(
        id = review3Id,
        characterId = characterIds[1], // Kai
        authorId = userId,
        rating = 5,
        text = "Kai is such a cool character! The shadow warrior concept is executed perfectly. Great for fantasy roleplay.",
        isAnonymous = false
    )

    complexQueryHelper.addReview(review3)
    reviewIds.add(review3Id)
    logger.info("Review 3 created successfully")

    // Отзыв 4 - на Kai (персонаж 2, анонимный)
    val review4Id = idGenerator.generateId(EntityType.REVIEW)
    logger.info("Creating review 4 with id: $review4Id")

    val review4 = ReviewDbo(
        id = review4Id,
        characterId = characterIds[1], // Kai
        authorId = userId,
        rating = 4,
        text = "Solid character design and great backstory. The dual blade concept is really cool!",
        isAnonymous = true
    )

    complexQueryHelper.addReview(review4)
    reviewIds.add(review4Id)
    logger.info("Review 4 created successfully")

    // Отзыв 5 - на Aria-7 (персонаж 3)
    val review5Id = idGenerator.generateId(EntityType.REVIEW)
    logger.info("Creating review 5 with id: $review5Id")

    val review5 = ReviewDbo(
        id = review5Id,
        characterId = characterIds[2], // Aria-7
        authorId = userId,
        rating = 5,
        text = "Fascinating AI character! The way she explores emotions and consciousness is really thought-provoking. Perfect for deep conversations.",
        isAnonymous = false
    )

    complexQueryHelper.addReview(review5)
    reviewIds.add(review5Id)
    logger.info("Review 5 created successfully")

    logger.info("All test reviews created successfully")
    return reviewIds
}