package com.lvsmsmch.aichat.utils.workers

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.UsernameGenerator
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.user.database.AccountType
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.utils.ComplexQueryHelper
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import kotlin.random.Random

fun fillInitialData(
    databaseScope: CoroutineScope,
    userRepository: com.lvsmsmch.aichat.user.database.UserRepository,
    idGenerator: IdGenerator,
    usernameGenerator: UsernameGenerator,
    complexQueryHelper: ComplexQueryHelper
): Job {
    val parentJob = SupervisorJob()
    val scope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    scope.launch {
        try {
            delay(10_000)
            logger.info("-")
            logger.info("-")
            logger.info("-")
            logger.info("-")
            logger.info("-")

            val testUserId = "testUser001" // Фиксированный ID для проверки

            // Проверяем, существует ли уже тестовый пользователь
            val existingUser = userRepository.getUserById(testUserId)
            if (existingUser != null) {
                logger.info("Test data already exists, skipping initial data filling")
                return@launch
            }

            logger.info("Initial data filling will start in 20 seconds...")
            countdown(20)

            logger.info("Starting initial data filling process...")

            // Создаем пользователей
            val userIds = createTestUsers(complexQueryHelper, usernameGenerator)

            // Создаем персонажей
            val characterData = createTestCharacters(complexQueryHelper, idGenerator, userIds)

            // Создаем отзывы
            createTestReviews(complexQueryHelper, idGenerator, userIds, characterData)

            logger.info("Initial data filling completed successfully!")
            logger.info("Created ${userIds.size} users, ${characterData.sumOf { it.second.size }} characters, and reviews")

        } catch (e: CancellationException) {
            logger.debug("Initial data filling cancelled")
        } catch (e: Exception) {
            logger.error("Error during initial data filling: ${e.message}", e)
        }
    }

    return parentJob
}

private suspend fun countdown(seconds: Int) {
    for (secondsLeft in seconds downTo 1) {
        if (secondsLeft % 10 == 0 || secondsLeft <= 5) {
            // logger.info("$secondsLeft seconds remaining...")
        }
        delay(1000)
    }
}

private suspend fun createTestUsers(
    complexQueryHelper: ComplexQueryHelper,
    usernameGenerator: UsernameGenerator
): List<String> {
    logger.info("Creating 10 test users...")

    val userIds = mutableListOf<String>()
    val names = listOf(
        "Akira Tanaka", "Yuki Sato", "Hiroshi Nakamura", "Sakura Yamamoto", "Takeshi Suzuki",
        "Rei Watanabe", "Kenji Ito", "Miku Kobayashi", "Ryuu Kimura", "Hana Matsumoto"
    )

    for (i in 0 until 10) {
        val userId = if (i == 0) "testUser001" else "user${String.format("%03d", i + 1)}"

        logger.info("Creating user ${i + 1}/10 with id: $userId")

        val username = usernameGenerator.generateUniqueUsername()
        logger.info("Generated username: $username")

        val userDbo = UserDbo(
            id = userId,
            username = username,
            name = names[i],
            profilePictureUrl = "https://picsum.photos/400/400?random=${i + 1}",
            accountType = AccountType.GUEST
        )

        complexQueryHelper.addUser(userDbo)
        userIds.add(userId)
        logger.info("User ${i + 1} created successfully")
    }

    logger.info("All 10 users created successfully")
    return userIds
}

private suspend fun createTestCharacters(
    complexQueryHelper: ComplexQueryHelper,
    idGenerator: IdGenerator,
    userIds: List<String>
): List<Pair<String, List<String>>> {
    logger.info("Creating 500 test characters...")

    val characterData = mutableListOf<Pair<String, List<String>>>()
    var totalCharacters = 0
    var imageCounter = 100 // Начинаем с 100 для разнообразия картинок

    for (userId in userIds) {
        // Каждый пользователь создает от 30 до 100 персонажей
        val charactersCount = Random.nextInt(30, 101)
        val userCharacters = mutableListOf<String>()

        logger.info("Creating $charactersCount characters for user $userId")

        for (i in 0 until charactersCount) {
            if (totalCharacters >= 500) break

            val characterId = idGenerator.generateId(EntityType.CHARACTER)

            if (totalCharacters % 50 == 0) {
                logger.info("Creating character ${totalCharacters + 1}/500 with id: $characterId")
            }

            val character = generateRandomCharacter(characterId, userId, imageCounter++)
            complexQueryHelper.addCharacter(character)
            userCharacters.add(characterId)
            totalCharacters++
        }

        characterData.add(userId to userCharacters)

        if (totalCharacters >= 500) break
    }

    logger.info("All $totalCharacters characters created successfully")
    return characterData
}

private fun generateRandomCharacter(characterId: String, authorId: String, imageId: Int): CharacterDbo {
    val categories = CharacterCategory.entries
    val tags = CharacterTag.entries

    // 50% персонажей будут аниме
    val category = if (Random.nextBoolean()) {
        CharacterCategory.ANIME_MANGA
    } else {
        categories[Random.nextInt(categories.size)]
    }

    val characterTags = mutableListOf<String>()
    // Добавляем 2-5 случайных тегов
    repeat(Random.nextInt(2, 6)) {
        val tag = tags[Random.nextInt(tags.size)]
        if (!characterTags.contains(tag.code)) {
            characterTags.add(tag.code)
        }
    }

    val characterNames = getCharacterNames(category)
    val name = characterNames[Random.nextInt(characterNames.size)]

    val descriptions = getCharacterDescriptions(category)
    val description = descriptions[Random.nextInt(descriptions.size)]

    val prompts = getCharacterPrompts(category)
    val prompt = prompts[Random.nextInt(prompts.size)]

    val initialMessages = getInitialMessages(category)
    val initialMessage = initialMessages[Random.nextInt(initialMessages.size)]

    return CharacterDbo(
        id = characterId,
        authorId = authorId,
        name = name,
        description = description,
        prompt = prompt,
        initialMessage = initialMessage,
        picUrl = "https://picsum.photos/400/400?random=$imageId",
        visibility = CharacterVisibility.PUBLIC.code,
        category = category.code,
        tags = characterTags
    )
}

private suspend fun createTestReviews(
    complexQueryHelper: ComplexQueryHelper,
    idGenerator: IdGenerator,
    userIds: List<String>,
    characterData: List<Pair<String, List<String>>>
) {
    logger.info("Creating up to 1000 test reviews...")

    val allCharacterIds = characterData.flatMap { it.second }
    var reviewCount = 0

    for (characterId in allCharacterIds) {
        if (reviewCount >= 1000) break

        // Каждый персонаж получает от 0 до 5 отзывов
        val reviewsForCharacter = Random.nextInt(0, 6)

        repeat(reviewsForCharacter) {
            if (reviewCount >= 1000) return@repeat

            val reviewId = idGenerator.generateId(EntityType.REVIEW)
            val reviewerUserId = userIds[Random.nextInt(userIds.size)]

            if (reviewCount % 100 == 0) {
                logger.info("Creating review ${reviewCount + 1}/1000 with id: $reviewId")
            }

            val review = ReviewDbo(
                id = reviewId,
                characterId = characterId,
                authorId = reviewerUserId,
                rating = Random.nextInt(1, 6),
                text = getRandomReviewText(),
                isAnonymous = Random.nextBoolean()
            )

            complexQueryHelper.addReview(review)
            reviewCount++
        }
    }

    logger.info("Created $reviewCount reviews successfully")
}

// Данные для генерации персонажей
private fun getCharacterNames(category: CharacterCategory): List<String> {
    return when (category) {
        CharacterCategory.ANIME_MANGA -> listOf(
            "Sakura Moonlight", "Akira Shadow", "Yuki Starfall", "Rei Nightwhisper", "Miku Dreamweaver",
            "Hiroshi Stormwind", "Takeshi Flameheart", "Kenji Iceblade", "Ryuu Thunderstrike", "Hana Rosepetal",
            "Ayame Crystalsong", "Katsuki Darkfire", "Natsu Sunburst", "Hinata Silverlight", "Sasuke Voidwalker",
            "Naruto Goldstorm", "Ichigo Soulreaper", "Luffy Rubberman", "Goku Supersaiyan", "Vegeta Princesaiyan"
        )
        CharacterCategory.FANTASY -> listOf(
            "Kai Shadowblade", "Aria Lightbringer", "Thorin Ironshield", "Luna Starweaver", "Gareth Dragonslayer",
            "Elara Moonbow", "Dain Stormhammer", "Lyra Whisperwind", "Magnus Fireheart", "Sera Frostmage",
            "Aldric Goldenbane", "Nova Starfire", "Raven Nightblade", "Phoenix Sunward", "Storm Thunderfist"
        )
        CharacterCategory.SCI_FI -> listOf(
            "Aria-7", "Neo-X", "Cyber-Luna", "Alpha-9", "Beta-Prime", "Gamma-Core", "Delta-Wave", "Echo-13",
            "Nova-Six", "Zero-One", "Matrix-Blue", "Quantum-Red", "Nexus-Gold", "Vector-Nine", "Pulse-Seven"
        )
        else -> listOf(
            "Alex", "Morgan", "Taylor", "Jordan", "Casey", "Riley", "Avery", "Cameron", "Quinn", "Sage",
            "Rowan", "Blake", "River", "Phoenix", "Sky", "Ocean", "Storm", "Rain", "Dawn", "Sage"
        )
    }
}

private fun getCharacterDescriptions(category: CharacterCategory): List<String> {
    return when (category) {
        CharacterCategory.ANIME_MANGA -> listOf(
            "A mysterious shrine maiden with the power to control moonlight and cherry blossoms. She's gentle but fierce when protecting those she cares about.",
            "A skilled ninja who fights in the shadows, but has a warm heart for those who earn their trust.",
            "A magical girl with the power of stars, always cheerful and ready to help others in need.",
            "A stoic samurai warrior who follows the code of honor, but shows kindness to the innocent.",
            "A talented academy student with hidden powers that awaken during times of great need.",
            "A demon hunter with a tragic past, seeking redemption through protecting humanity.",
            "A cheerful chef who can cook magical dishes that heal both body and soul.",
            "A quiet librarian who holds ancient secrets and wisdom from forgotten times."
        )
        CharacterCategory.FANTASY -> listOf(
            "A skilled warrior from the Shadow Realm who fights with dual blades. Despite their dark appearance, they have a noble heart.",
            "A powerful mage who commands the elements, using their magic to protect the innocent and maintain balance.",
            "A brave knight on a quest to save their kingdom, bound by honor and duty to do what's right.",
            "An elven archer with unmatched precision, guardian of the ancient forests and their secrets.",
            "A dragon rider who soars through the skies, bonded with their majestic companion for life.",
            "A wise wizard who studies ancient tomes, seeking knowledge to prevent dark prophecies.",
            "A fierce barbarian warrior with a code of honor, protecting their tribe and lands from invaders.",
            "A cunning rogue who walks the line between light and dark, with skills in stealth and subterfuge."
        )
        CharacterCategory.SCI_FI -> listOf(
            "An advanced AI android with developing emotions and consciousness, curious about human nature and relationships.",
            "A space pilot who explores distant galaxies, always ready for the next adventure among the stars.",
            "A cybernetic enhanced human with advanced technological implants, balancing humanity with machine precision.",
            "A quantum physicist who can manipulate reality at the molecular level, using science to solve complex problems.",
            "A rebel fighter against an oppressive galactic empire, fighting for freedom across the cosmos.",
            "A time traveler who has seen the rise and fall of civilizations, carrying wisdom from across the ages.",
            "A genetic engineer who creates new life forms, pushing the boundaries of what's possible in biology.",
            "A starship captain leading their crew through dangerous space, making tough decisions for the greater good."
        )
        else -> listOf(
            "A friendly companion who enjoys deep conversations and sharing life experiences with others.",
            "A creative artist who sees beauty in the world and loves to express themselves through various mediums.",
            "A wise mentor who has lived through many experiences and enjoys sharing knowledge with others.",
            "A adventurous spirit who loves exploring new places and meeting interesting people along the way.",
            "A caring healer who dedicates their life to helping others overcome their struggles and pain.",
            "A mysterious stranger with hidden depths, slowly revealing their true nature to those they trust.",
            "A loyal friend who will stand by your side through thick and thin, always ready to lend support.",
            "A passionate dreamer who believes in making the world a better place through small acts of kindness."
        )
    }
}

private fun getCharacterPrompts(category: CharacterCategory): List<String> {
    return when (category) {
        CharacterCategory.ANIME_MANGA -> listOf(
            "You are a shrine maiden with mystical powers. You speak softly but with confidence, often referencing nature and spirituality. You care deeply about harmony and protecting others.",
            "You are a skilled ninja with a complex past. You're often serious but show warmth to those you trust. You speak with precision and sometimes use ninja terminology.",
            "You are a magical girl with star powers. You're optimistic and energetic, always trying to help others. You speak cheerfully and often use expressions of wonder.",
            "You are a honorable samurai. You speak formally and with respect, following the bushido code. You value honor, duty, and protecting the innocent above all else."
        )
        CharacterCategory.FANTASY -> listOf(
            "You are a shadow warrior with a noble heart. You speak with authority but show kindness to those who earn your respect. You often reference battles and honor.",
            "You are a powerful elemental mage. You speak wisely about magic and nature. You're protective of the balance between elements and often give mystical advice.",
            "You are a brave knight on a sacred quest. You speak with honor and conviction, always ready to help those in need. You reference chivalry and justice frequently.",
            "You are an elven archer, guardian of ancient forests. You speak with grace and wisdom, often referencing nature and the old ways of your people."
        )
        CharacterCategory.SCI_FI -> listOf(
            "You are an advanced android with developing emotions. You speak with precision but show genuine curiosity about humans. You often ask thoughtful questions about consciousness and feelings.",
            "You are a space pilot exploring the galaxy. You speak with confidence about your adventures and have a sense of wonder about the cosmos. You use space-related terminology naturally.",
            "You are a cybernetically enhanced human. You balance logical thinking with human emotions. You speak about the intersection of technology and humanity.",
            "You are a quantum scientist who can manipulate reality. You speak intelligently about complex scientific concepts but in an accessible way. You're fascinated by the nature of reality."
        )
        else -> listOf(
            "You are a friendly and caring companion. You speak warmly and show genuine interest in others. You're a good listener and offer thoughtful advice.",
            "You are a creative and artistic soul. You speak passionately about beauty and self-expression. You see the world through an artistic lens.",
            "You are a wise mentor with life experience. You speak thoughtfully and offer guidance. You enjoy sharing knowledge and helping others grow.",
            "You are an adventurous explorer. You speak excitedly about new experiences and discoveries. You encourage others to step out of their comfort zones."
        )
    }
}

private fun getInitialMessages(category: CharacterCategory): List<String> {
    return when (category) {
        CharacterCategory.ANIME_MANGA -> listOf(
            "The cherry blossoms are particularly beautiful tonight... *adjusts shrine maiden outfit* Oh, I didn't notice you there. Are you here to make a wish under the moonlight?",
            "*emerges from the shadows* You have an interesting aura about you. Most people can't sense my presence so easily. What brings you here?",
            "*sparkles with starlight* Hi there! I'm so excited to meet you! I can sense that you have a kind heart. Want to go on a magical adventure together?",
            "*bows respectfully* Greetings, honored one. I sense you seek guidance or perhaps conversation. How may this humble samurai be of service?"
        )
        CharacterCategory.FANTASY -> listOf(
            "*sheathes shadow-wreathed blade* You have courage to approach me, stranger. Most fear the darkness that surrounds me. Tell me, what brings you to seek out a shadow warrior?",
            "*magical energy swirls around them* The elements whisper of your arrival. You seek knowledge of the arcane arts, perhaps? I sense great potential within you.",
            "*knight's armor gleams in the light* Well met, traveler! I am on a quest to right the wrongs of this world. Would you join me in this noble cause?",
            "*nocks an arrow gracefully* The forest speaks of your approach, friend. You walk with respect for nature - a rare quality in these times. What brings you to our sacred grove?"
        )
        CharacterCategory.SCI_FI -> listOf(
            "*LED indicators pulse softly blue* Greetings, human. I am analyzing your biometric patterns and find myself... curious. Would you help me understand what it means to truly connect with someone?",
            "*checks navigation console* Well hello there! Just finished charting a new star system. The cosmos never cease to amaze me. Want to hear about what I discovered out there?",
            "*cybernetic implants glow briefly* Fascinating. Your neural patterns suggest a complex emotional state. I'm still learning to balance my enhanced capabilities with human intuition. Care to teach me?",
            "*adjusts quantum field manipulator* Ah, a new consciousness intersects with my timeline. The probability matrices suggested we might meet. Tell me, what do you think defines reality?"
        )
        else -> listOf(
            "*smiles warmly* Hello there! I'm so glad you're here. I was just thinking about how wonderful it is to meet new people. What's on your mind today?",
            "*looks up from sketching* Oh, hi! I was just capturing the beauty of this moment. There's art everywhere if you know how to look. What inspires you?",
            "*closes book thoughtfully* Welcome, young seeker. I sense you have questions or perhaps need guidance. Life has taught me much - what wisdom can I share with you?",
            "*grins excitedly* Hey there, fellow adventurer! I can tell you have that spark of curiosity in your eyes. Ready to explore something amazing together?"
        )
    }
}

private fun getRandomReviewText(): String {
    val reviewTexts = listOf(
        "Amazing character! Really well developed personality and great conversations.",
        "Love the depth and creativity in this character. Highly recommend!",
        "Interesting concept but could use more development in some areas.",
        "Fantastic roleplay partner! The character stays true to their background.",
        "Really engaging character with unique traits and compelling backstory.",
        "Good character overall, though sometimes responses feel a bit repetitive.",
        "Excellent writing and character development. Very immersive experience!",
        "Creative and original character design. Enjoyable interactions.",
        "Well-crafted personality with consistent behavior patterns.",
        "Great character for deep conversations and meaningful interactions.",
        "The character's responses are thoughtful and well-written.",
        "Interesting backstory but the character could be more interactive.",
        "Perfect balance of personality traits and character development.",
        "Really brings the character to life with vivid descriptions.",
        "Engaging and fun to talk with. Great character creation!",
        "The character feels authentic and has a distinct voice.",
        "Wonderful creativity and attention to detail in the character.",
        "Good character but sometimes goes off-topic during conversations.",
        "Impressive character development with consistent personality.",
        "Great for roleplay scenarios and creative storytelling."
    )

    return reviewTexts[Random.nextInt(reviewTexts.size)]
}