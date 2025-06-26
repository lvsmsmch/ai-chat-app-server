package com.lvsmsmch.aichat.character.database

enum class CharacterSortCriteria(val code: Int) {
    NEWEST(0),
    OLDEST(1),
    HIGHEST_RATING(2),
    LOWEST_RATING(3),
    MOST_POPULAR(4),
    LEAST_POPULAR(5),
    TRENDING(6),
    RECOMMENDED(7)
}