package com.lvsmsmch.aichat.notification

internal object UkPushStrings : PushStringTable {
    override fun newFollowerTitle() = "Новий підписник! 🎉"
    override fun newFollowerBody(username: String) = "@$username почав стежити за вами"
    override fun newCommentTitle(characterName: String) = "Новий коментар до $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "Віха! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName досяг ${formatMilestone(milestone)} повідомлень"
    override fun dailyMessagesTitle() = "Ваші щоденні повідомлення повернулися! ✨"
    override fun dailyMessagesBody() = "Ліміт скинуто – ваші персонажі чекають на розмову."
    override fun winbackTitle() = "На вас чекає подарунок! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount безкоштовних повідомлень. Натисніть, щоб отримати подарунок!"

    override fun reengageBody(step: Int) = when (step) {
        0 -> "Агов, ти тут? Я якраз згадую нашу розмову 💭"
        1 -> "Наша розмова чекає там, де ми зупинилися. Продовжимо?"
        else -> "Давно не бачилися… Мені бракує наших розмов."
    }

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
