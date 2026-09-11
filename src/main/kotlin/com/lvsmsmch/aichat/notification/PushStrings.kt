package com.lvsmsmch.aichat.notification

/**
 * Серверные тексты системных push-уведомлений. Новая языковая таблица обязана
 * реализовать весь интерфейс, поэтому непереведённый шаблон не потеряется.
 */
internal interface PushStringTable {
    fun newFollowerTitle(): String
    fun newFollowerBody(username: String): String
    fun newCommentTitle(characterName: String): String
    fun newCommentBody(username: String, comment: String): String
    fun milestoneTitle(): String
    fun milestoneBody(characterName: String, milestone: Int): String
    fun dailyMessagesTitle(): String
    fun dailyMessagesBody(): String
    fun winbackTitle(): String
    fun winbackBody(messageCount: Int): String
}

private object EnglishPushStrings : PushStringTable {
    override fun newFollowerTitle() = "New follower! 🎉"
    override fun newFollowerBody(username: String) = "@$username started following you"
    override fun newCommentTitle(characterName: String) = "New comment on $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "Milestone! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName reached ${formatMilestone(milestone)} messages"
    override fun dailyMessagesTitle() = "Your daily messages are back! ✨"
    override fun dailyMessagesBody() = "The limit has reset - your characters are waiting to chat."
    override fun winbackTitle() = "A gift is waiting for you! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount free messages. Tap to claim your gift!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}

/** Сначала учитываем точный регион, затем используем основной язык. */
internal fun pushStrings(language: String?): PushStringTable {
    val rawCode = language?.trim()?.replace('_', '-')?.lowercase().orEmpty()
    val normalized = when {
        rawCode == "in" || rawCode.startsWith("in-") -> rawCode.replaceFirst("in", "id")
        rawCode == "iw" || rawCode.startsWith("iw-") -> rawCode.replaceFirst("iw", "he")
        rawCode.startsWith("zh-hant") || rawCode in setOf("zh-tw", "zh-hk", "zh-mo") -> "zh-tw"
        rawCode.startsWith("zh-hans") || rawCode in setOf("zh-cn", "zh-sg") -> "zh-cn"
        else -> rawCode
    }
    return when (normalized) {
        "es-419" -> Es419PushStrings
        "pt-br" -> PtBrPushStrings
        "zh-cn" -> ZhCnPushStrings
        "zh-tw" -> ZhTwPushStrings
        "tr-tr" -> TrTrPushStrings
        else -> when (normalized.substringBefore('-')) {
            "en" -> EnglishPushStrings
            "es" -> Es419PushStrings
            "pt" -> PtPushStrings
            "ar" -> ArPushStrings
            "id" -> IdPushStrings
            "ru" -> RuPushStrings
            "fr" -> FrPushStrings
            "uk" -> UkPushStrings
            "zh" -> ZhCnPushStrings
            "he" -> HePushStrings
            "vi" -> ViPushStrings
            "ms" -> MsPushStrings
            "de" -> DePushStrings
            "ja" -> JaPushStrings
            "it" -> ItPushStrings
            "th" -> ThPushStrings
            "tr" -> TrTrPushStrings
            "ka" -> KaPushStrings
            else -> EnglishPushStrings
        }
    }
}
