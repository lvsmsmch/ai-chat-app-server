package com.lvsmsmch.aichat.notification

internal object PtPushStrings : PushStringTable {
    override fun newFollowerTitle() = "Novo seguidor! 🎉"
    override fun newFollowerBody(username: String) = "@$username começou a segui-lo(a)"
    override fun newCommentTitle(characterName: String) = "Novo comentário em $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "Marco! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName atingiu ${formatMilestone(milestone)} mensagens"
    override fun dailyMessagesTitle() = "As suas mensagens diárias voltaram! ✨"
    override fun dailyMessagesBody() = "O limite foi redefinido - as suas personagens estão à espera de conversar."
    override fun winbackTitle() = "Um presente está à sua espera! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount mensagens gratuitas. Toque para reclamar o seu presente!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
