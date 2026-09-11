package com.lvsmsmch.aichat.notification

internal object Es419PushStrings : PushStringTable {
    override fun newFollowerTitle() = "¡Nuevo seguidor! 🎉"
    override fun newFollowerBody(username: String) = "@$username comenzó a seguirte"
    override fun newCommentTitle(characterName: String) = "Nuevo comentario en $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "¡Hito! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName alcanzó ${formatMilestone(milestone)} mensajes"
    override fun dailyMessagesTitle() = "¡Tus mensajes diarios han vuelto! ✨"
    override fun dailyMessagesBody() = "El límite se ha restablecido; tus personajes esperan para chatear."
    override fun winbackTitle() = "¡Un regalo te espera! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount mensajes gratis. ¡Toca para reclamar tu regalo!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
