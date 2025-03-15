package com.lvsmsmch.aichat.utils

enum class CharacterFilter(val code: Int) {
    NEWEST(0),
    OLDEST(1),
    HIGHEST_RATING(2),
    LOWEST_RATING(3),
    MOST_POPULAR(4),
    LEAST_POPULAR(5),
}