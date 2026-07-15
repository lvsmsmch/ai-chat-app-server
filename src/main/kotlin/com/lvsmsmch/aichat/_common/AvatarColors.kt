package com.lvsmsmch.aichat._common

/**
 * Палитра цветов аватарок. Цвет выдаётся ОДИН раз при создании юзера/персонажа/чата
 * и дальше статичен для всех клиентов.
 */
object AvatarColors {
    val palette = listOf(
        "#7C5CBF", "#5C6BC0", "#42818C", "#3E7A5E", "#8C7A42",
        "#A05744", "#96455E", "#4A6FA5", "#6B8E4E", "#B07D3C",
        "#815CA6", "#3C8A8A", "#5E5CA6", "#4E8E6B", "#A6635C",
    )
    fun random(): String = palette.random()
}
