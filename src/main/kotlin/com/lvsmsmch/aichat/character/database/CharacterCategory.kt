package com.lvsmsmch.aichat.character.database

enum class CharacterCategory(val code: String) {
    ANIME_MANGA("anime_manga"),
    MOVIES_TV_SHOWS("movies_tv_shows"),
    GAMES("games"),
    BOOKS_COMICS("books_comics"),
    FANTASY("fantasy"),
    HISTORICAL("historical"),
    MODERN("modern"),
    ORIGINAL("original"),
    OTHER("other");

    companion object {
        fun getByCode(code: String) = entries.first { it.code == code }
    }
}