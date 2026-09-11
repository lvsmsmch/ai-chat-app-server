package com.lvsmsmch.aichat.notification

internal object FrPushStrings : PushStringTable {
    override fun newFollowerTitle() = "Nouveau follower ! 🎉"
    override fun newFollowerBody(username: String) = "@$username a commencé à vous suivre"
    override fun newCommentTitle(characterName: String) = "Nouveau commentaire sur $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username : $comment"
    override fun milestoneTitle() = "Jalon ! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName a atteint ${formatMilestone(milestone)} messages"
    override fun dailyMessagesTitle() = "Vos messages quotidiens sont de retour ! ✨"
    override fun dailyMessagesBody() = "La limite a été réinitialisée - vos personnages attendent de discuter."
    override fun winbackTitle() = "Un cadeau vous attend ! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount messages gratuits. Appuyez pour réclamer votre cadeau !"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
