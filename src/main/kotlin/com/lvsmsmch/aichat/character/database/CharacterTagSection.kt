package com.lvsmsmch.aichat.character.database

enum class CharacterTagSection(val code: String, val color: String, val tags: List<CharacterTag>) {
    RELATIONSHIP(
        code = "relationship",
        color = "E91E63",
        tags = listOf(
            CharacterTag.BOYFRIEND,
            CharacterTag.GIRLFRIEND,
            CharacterTag.FRIEND,
            CharacterTag.MENTOR,
            CharacterTag.RIVAL,
            CharacterTag.CLASSMATE,
            CharacterTag.NEIGHBOR,
            CharacterTag.EX_BOYFRIEND,
            CharacterTag.EX_GIRLFRIEND,
            CharacterTag.ROOMMATE,
            CharacterTag.COWORKER,
            CharacterTag.STRANGER
        )
    ),
    PERSONALITY(
        code = "personality",
        color = "9C27B0",
        tags = listOf(
            CharacterTag.ROMANTIC,
            CharacterTag.FLIRTY,
            CharacterTag.SHY,
            CharacterTag.DOMINANT,
            CharacterTag.CARING,
            CharacterTag.TSUNDERE,
            CharacterTag.YANDERE,
            CharacterTag.MYSTERIOUS,
            CharacterTag.CHEERFUL,
            CharacterTag.SERIOUS
        )
    ),
    ROLE(
        code = "role",
        color = "2196F3",
        tags = listOf(
            CharacterTag.HERO,
            CharacterTag.VILLAIN,
            CharacterTag.ANTI_HERO,
            CharacterTag.PRINCE_PRINCESS,
            CharacterTag.WARRIOR,
            CharacterTag.MAGE,
            CharacterTag.DETECTIVE,
            CharacterTag.TEACHER,
            CharacterTag.BOSS
        )
    ),
    APPEARANCE(
        code = "appearance",
        color = "4CAF50",
        tags = listOf(
            CharacterTag.DARK_HAIR,
            CharacterTag.LIGHT_HAIR,
            CharacterTag.TALL,
            CharacterTag.MUSCULAR,
            CharacterTag.CUTE,
            CharacterTag.ELEGANT
        )
    ),
    TRAITS(
        code = "traits",
        color = "FF9800",
        tags = listOf(
            CharacterTag.STRONG,
            CharacterTag.SMART,
            CharacterTag.RICH,
            CharacterTag.POPULAR,
            CharacterTag.LONELY,
            CharacterTag.DANGEROUS
        )
    )
}