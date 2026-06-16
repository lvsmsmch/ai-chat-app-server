package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.utils.ValidationException

enum class CharacterTag(val code: String) {
    BOYFRIEND("boyfriend"),
    GIRLFRIEND("girlfriend"),
    FRIEND("friend"),
    MENTOR("mentor"),
    RIVAL("rival"),
    CLASSMATE("classmate"),
    NEIGHBOR("neighbor"),
    EX_BOYFRIEND("ex_boyfriend"),
    EX_GIRLFRIEND("ex_girlfriend"),
    ROOMMATE("roommate"),
    COWORKER("coworker"),
    STRANGER("stranger"),

    ROMANTIC("romantic"),
    FLIRTY("flirty"),
    SHY("shy"),
    DOMINANT("dominant"),
    CARING("caring"),
    TSUNDERE("tsundere"),
    YANDERE("yandere"),
    MYSTERIOUS("mysterious"),
    CHEERFUL("cheerful"),
    SERIOUS("serious"),

    HERO("hero"),
    VILLAIN("villain"),
    ANTI_HERO("anti_hero"),
    PRINCE_PRINCESS("prince_princess"),
    WARRIOR("warrior"),
    MAGE("mage"),
    DETECTIVE("detective"),
    TEACHER("teacher"),
    BOSS("boss"),

    DARK_HAIR("dark_hair"),
    LIGHT_HAIR("light_hair"),
    TALL("tall"),
    MUSCULAR("muscular"),
    CUTE("cute"),
    ELEGANT("elegant"),

    STRONG("strong"),
    SMART("smart"),
    RICH("rich"),
    POPULAR("popular"),
    LONELY("lonely"),
    DANGEROUS("dangerous");

    companion object {
        fun getByCode(code: String) = CharacterTag.entries.first { it.code == code }
        fun fromString(tags: String): List<CharacterTag> {
            return if (tags.isBlank()) {
                emptyList()
            } else {
                tags.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { tag ->
                        try {
                            CharacterTag.getByCode(tag)
                        } catch (e: IllegalArgumentException) {
                            throw ValidationException("Unknown tag '$tag'")
                        }
                    }
            }
        }
    }
}
