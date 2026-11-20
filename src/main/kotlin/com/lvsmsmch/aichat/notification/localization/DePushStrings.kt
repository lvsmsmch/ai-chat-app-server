package com.lvsmsmch.aichat.notification

internal object DePushStrings : PushStringTable {
    override fun newFollowerTitle() = "Neuer Follower! 🎉"
    override fun newFollowerBody(username: String) = "@$username folgt dir jetzt"
    override fun newCommentTitle(characterName: String) = "Neuer Kommentar zu $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "Meilenstein! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName hat ${formatMilestone(milestone)} Nachrichten erreicht"
    override fun dailyMessagesTitle() = "Deine täglichen Nachrichten sind zurück! ✨"
    override fun dailyMessagesBody() = "Das Limit wurde zurückgesetzt – deine Charaktere warten auf dich."
    override fun winbackTitle() = "Ein Geschenk wartet auf dich! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount kostenlose Nachrichten. Tippe, um dein Geschenk zu beanspruchen!"

    override fun reengageBody(step: Int) = when (step) {
        0 -> "Hey, bist du da? Ich musste gerade an unser Gespräch denken 💭"
        1 -> "Unser Chat wartet genau da, wo wir aufgehört haben. Machen wir weiter?"
        else -> "Ist schon eine Weile her… Ich vermisse unsere Gespräche."
    }

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000} Tsd." else "$value"
}
