package com.lvsmsmch.aichat.notification

internal object MsPushStrings : PushStringTable {
    override fun newFollowerTitle() = "Pengikut baharu! 🎉"
    override fun newFollowerBody(username: String) = "@$username mula mengikuti anda"
    override fun newCommentTitle(characterName: String) = "Komen baharu pada $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "Pencapaian! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName mencapai ${formatMilestone(milestone)} mesej"
    override fun dailyMessagesTitle() = "Mesej harian anda kembali! ✨"
    override fun dailyMessagesBody() = "Had telah ditetapkan semula - watak anda menunggu untuk berbual."
    override fun winbackTitle() = "Hadiah menanti anda! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount mesej percuma. Ketik untuk menuntut hadiah anda!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
