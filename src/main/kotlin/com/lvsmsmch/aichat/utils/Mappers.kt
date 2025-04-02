package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.db.repositories.content.CharacterDbo
import com.lvsmsmch.aichat.db.repositories.content.ReviewDbo
import com.lvsmsmch.aichat.db.repositories.content.UserDbo
import com.lvsmsmch.aichat.network.dto_objects.CharacterDetailsDto
import com.lvsmsmch.aichat.network.dto_objects.CharacterDto
import com.lvsmsmch.aichat.network.dto_objects.ReviewDto
import com.lvsmsmch.aichat.network.dto_objects.UserDto

fun UserDbo.toUserDto(): UserDto {
    return UserDto(
        id = id,
        createdAt = createdAt,
        username = username,
        name = name,
        profilePicUrl = profilePicUrl,
    )
}

fun CharacterDbo.toCharacterDto(): CharacterDto {
    return CharacterDto(
        id = id,
        publishedAt = publishedAt.toString(),
        publisherUsername = publisherUsername,
        name = name,
        description = description,
        picUrl = picUrl,
        totalMessages = totalMessages,
        totalReviews = totalReviews,
        averageRating = averageRating,
        totalChats = totalChats,
    )
}

fun CharacterDbo.toCharacterDetailsDto(
    publisher: UserDto?,
    recentReviews: List<ReviewDto>
): CharacterDetailsDto {
    return CharacterDetailsDto(
        prompt = prompt,
        publisher = publisher,
        recentReviews = recentReviews
    )
}


fun ReviewDbo.toReviewDto(publisher: UserDto?): ReviewDto {
    return ReviewDto(
        id = id,
        publishedAt = publishedAt.toString(),
        publisher = publisher,
        rating = rating,
        text = text,
    )
}