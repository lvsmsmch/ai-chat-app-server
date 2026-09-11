package com.lvsmsmch.aichat.notification

internal object RuPushStrings : PushStringTable {
    override fun newFollowerTitle() = "Новый подписчик! 🎉"
    override fun newFollowerBody(username: String) = "@$username начал(а) следить за вами"
    override fun newCommentTitle(characterName: String) = "Новый комментарий к $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "Важное событие! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName достиг(ла) ${formatMilestone(milestone)} сообщений"
    override fun dailyMessagesTitle() = "Ваши ежедневные сообщения вернулись! ✨"
    override fun dailyMessagesBody() = "Лимит сброшен — персонажи ждут вашего сообщения."
    override fun winbackTitle() = "Вас ждет подарок! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount бесплатных сообщений. Нажмите, чтобы получить подарок!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
