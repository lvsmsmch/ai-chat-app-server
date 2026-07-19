package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.character.database.CharacterCategory
import com.lvsmsmch.aichat.character.database.CharacterSortCriteria
import com.lvsmsmch.aichat.character.database.CharacterTag
import com.lvsmsmch.aichat.character.database.CharacterVisibility
import com.lvsmsmch.aichat.review.database.ReviewSortCriteria
import java.io.File
import java.io.IOException

fun validateDeviceId(deviceId: String) {
    if (deviceId.length < 10 || deviceId.length > 40) {
        throw ValidationException("deviceId must be in a range from 10 to 40 characters length")
    }
}

private val USERNAME_REGEX = Regex("^[\\p{L}\\p{N}_-]{3,20}$")

fun validateUserUsername(username: String) {
    if (username.length < 3 || username.length > 20) {
        throw ValidationException("Username must be between 3 and 20 characters")
    }
    // Буквы любого алфавита (кириллица ок), цифры, точка/подчёркивание/дефис.
    // Отсекает пробелы, переносы строк, эмодзи и управляющие символы —
    // ничего, что могло бы сломать UI или экзотически повести себя в БД.
    if (!USERNAME_REGEX.matches(username)) {
        throw ValidationException("Username can contain only letters, digits, \"_\" and \"-\"")
    }
}

fun validateUserName(name: String) {
    if (name.length > 40) {
        throw ValidationException("Name must be less than 40 characters")
    }
}

fun validateUserBio(bio: String) {
    if (bio.length > 1000) {
        throw ValidationException("Name must be less than 1000 characters")
    }
}

fun validateUserPicture(pictureFile: File) {
    validatePicture(
        pictureFile = pictureFile,
        maxSizeBytes = 1 * 1024 * 1024,
        minDimensionsPixels = 100 to 100,
        maxDimensionsPixels = 2000 to 2000,
        aspectRatio = 1.0f
    )
}

fun validateReviewRating(rating: Int) {
    if (rating !in 1..5) {
        throw ValidationException("Rating must be a number between 1 and 5")
    }
}

fun validateCommentText(text: String) {
    if (text.isBlank()) {
        throw ValidationException("Comment text must not be blank")
    }
    if (text.length > 1000) {
        throw ValidationException("Text should not exceed 1000 characters")
    }
}

fun validateReviewText(text: String) {
    if (text.length > 1000) {
        throw ValidationException("Text should not exceed 1000 characters")
    }
}

fun validateCharacterName(name: String) {
    if (name.isEmpty() || name.length > 40) {
        throw ValidationException("Name must be between 1 and 40 characters")
    }
}

fun validateCharacterDescription(description: String) {
    if (description.length > 1000) {
        throw ValidationException("Description must be between 0 and 1000 characters")
    }
}

fun validateCharacterPrompt(prompt: String) {
    if (prompt.length > 500) {
        throw ValidationException("Prompt must be between 0 and 500 characters")
    }
}

fun validateCharacterInitialMessage(initialMessage: String) {
    if (initialMessage.isEmpty() || initialMessage.length > 500) {
        throw ValidationException("initialMessage must be between 1 and 500 characters")
    }
}

fun validateCharacterSearchQuery(searchQuery: String) {
    if (searchQuery.isEmpty() || searchQuery.length > 50) {
        throw ValidationException("Search query should be not empty and less than 50 characters")
    }
}

fun validateReviewSortCriteria(sortCriteria: Int) {
    if (!ReviewSortCriteria.entries.map { it.code }.contains(sortCriteria)) {
        throw ValidationException(
            "Unknown review sortCriteria: $sortCriteria" +
                    ", possible values are: ${ReviewSortCriteria.entries.map { it.code }.toList()}"
        )
    }
}

fun validateCharacterSortCriteria(sortCriteria: Int) {
    if (!CharacterSortCriteria.entries.map { it.code }.contains(sortCriteria)) {
        throw ValidationException(
            "Unknown character sortCriteria: $sortCriteria" +
                    ", possible values are: ${CharacterSortCriteria.entries.map { it.code }.toList()}"
        )
    }
}


fun validateCharacterVisibility(visibility: Int) {
    if (!CharacterVisibility.entries.map { it.code }.contains(visibility)) {
        throw ValidationException(
            "Unknown character visibility: $visibility" +
                    ", possible values are: ${CharacterVisibility.entries.map { it.code }.toList()}"
        )
    }
}

fun validateCharacterCategory(category: String) {
    val existingCategoryCodes = CharacterCategory.entries.map { it.code }
    if (category !in existingCategoryCodes) {
        throw ValidationException(
            "Unknown character category: $category, possible values are: personalized, $existingCategoryCodes"
        )
    }
}

fun validateCharacterTags(tags: String) {
    if (tags.isBlank()) return

    val existingTagCodes = CharacterTag.entries.map { it.code }
    val tagsAsList = tags.split(",")
    tagsAsList.forEach { tag ->
        if (tag !in existingTagCodes) {
            throw ValidationException(
                "Unknown character tag: $tag, possible values are: $existingTagCodes"
            )
        }
    }
}

fun validateCharactersSize(size: Int) {
    if (size !in 1..10) {
        throw ValidationException("Unsupported size of characters (should be from 1 to 10)")
    }
}

fun validateCharacterPicture(pictureFile: File) {
    validatePicture(
        pictureFile = pictureFile,
        maxSizeBytes = 1 * 1024 * 1024,
        minDimensionsPixels = 100 to 100,
        maxDimensionsPixels = 2000 to 2000,
        aspectRatio = 1.0f
    )
}

fun validateMessageText(messageText: String) {
    if (messageText.length > 1000) {
        throw ValidationException("Message is too long")
    }
}

fun validateCustomChatName(customName: String) {
    if (customName.length > 100) {
        throw ValidationException("Chat name is too long")
    }
}

private fun validatePicture(
    pictureFile: File,
    maxSizeBytes: Int,
    minDimensionsPixels: Pair<Int, Int>,
    maxDimensionsPixels: Pair<Int, Int>,
    aspectRatio: Float
) {
    if (pictureFile.length() > maxSizeBytes) {
        throw ValidationException("Picture must be smaller than $maxSizeBytes bytes")
    }

    try {
        val inputStream = pictureFile.inputStream()
        val image = javax.imageio.ImageIO.read(inputStream)
        inputStream.close()

        if (image == null) {
            throw ValidationException("Uploaded file is not a valid image")
        }

        val minWidth = minDimensionsPixels.first
        val minHeight = minDimensionsPixels.second
        val maxWidth = maxDimensionsPixels.first
        val maxHeight = maxDimensionsPixels.second

        val width = image.width
        val height = image.height

        if (width < minWidth || height < minHeight) {
            throw ValidationException("Image dimensions must be at least ${minWidth}x${minHeight} pixels")
        }

        if (width > maxWidth || height > maxHeight) {
            throw ValidationException("Image dimensions must not exceed ${maxWidth}x${maxHeight} pixels")
        }

        if ((width.toFloat() / height.toFloat()) != aspectRatio) {
            throw ValidationException("Image aspect ratio should be $aspectRatio")
        }
    } catch (e: IOException) {
        throw ValidationException("Unable to process image: ${e.message}")
    } catch (e: Exception) {
        if (e is ValidationException) throw e
        throw ValidationException("Invalid image format")
    }
}