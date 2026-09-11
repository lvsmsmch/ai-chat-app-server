package com.lvsmsmch.aichat.notification

internal object IdPushStrings : PushStringTable {
    override fun newFollowerTitle() = "Pengikut baru! 🎉"
    override fun newFollowerBody(username: String) = "@$username mulai mengikuti Anda"
    override fun newCommentTitle(characterName: String) = "Komentar baru di $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "Pencapaian! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName mencapai ${formatMilestone(milestone)} pesan"
    override fun dailyMessagesTitle() = "Pesan harian Anda kembali! ✨"
    override fun dailyMessagesBody() = "Batas telah direset - karakter Anda menunggu untuk diajak bicara."
    override fun winbackTitle() = "Hadiah menanti Anda! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount pesan gratis. Ketuk untuk mengklaim hadiah Anda!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
