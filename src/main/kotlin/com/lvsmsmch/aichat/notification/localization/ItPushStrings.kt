package com.lvsmsmch.aichat.notification

internal object ItPushStrings : PushStringTable {
    override fun newFollowerTitle() = "Nuovo follower! 🎉"
    override fun newFollowerBody(username: String) = "@$username ha iniziato a seguirti"
    override fun newCommentTitle(characterName: String) = "Nuovo commento su $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "Traguardo! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName ha raggiunto ${formatMilestone(milestone)} messaggi"
    override fun dailyMessagesTitle() = "I tuoi messaggi giornalieri sono tornati! ✨"
    override fun dailyMessagesBody() = "Il limite è stato reimpostato: i tuoi personaggi aspettano di chattare."
    override fun winbackTitle() = "Un regalo ti aspetta! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount messaggi gratuiti. Tocca per reclamare il tuo regalo!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
