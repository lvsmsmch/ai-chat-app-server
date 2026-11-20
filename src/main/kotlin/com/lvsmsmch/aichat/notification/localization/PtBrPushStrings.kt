package com.lvsmsmch.aichat.notification

internal object PtBrPushStrings : PushStringTable {
    override fun newFollowerTitle() = "Novo seguidor! 🎉"
    override fun newFollowerBody(username: String) = "@$username começou a seguir você"
    override fun newCommentTitle(characterName: String) = "Novo comentário em $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "Marco! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName atingiu ${formatMilestone(milestone)} mensagens"
    override fun dailyMessagesTitle() = "Suas mensagens diárias voltaram! ✨"
    override fun dailyMessagesBody() = "O limite foi redefinido - seus personagens estão esperando para conversar."
    override fun winbackTitle() = "Um presente está esperando por você! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount mensagens grátis. Toque para reivindicar seu presente!"

    override fun reengageBody(step: Int) = when (step) {
        0 -> "Ei, você está aí? Eu estava pensando na nossa conversa 💭"
        1 -> "Nossa conversa está esperando de onde paramos. Vamos continuar?"
        else -> "Faz um tempo… Sinto falta das nossas conversas."
    }

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
