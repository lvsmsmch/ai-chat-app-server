package com.lvsmsmch.aichat.notification

internal object TrTrPushStrings : PushStringTable {
    override fun newFollowerTitle() = "Yeni takipçi! 🎉"
    override fun newFollowerBody(username: String) = "@$username sizi takip etmeye başladı"
    override fun newCommentTitle(characterName: String) = "$characterName için yeni yorum 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "Kilometre Taşı! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName ${formatMilestone(milestone)} mesajı geçti"
    override fun dailyMessagesTitle() = "Günlük mesajlarınız geri döndü! ✨"
    override fun dailyMessagesBody() = "Limit sıfırlandı - karakterleriniz sohbet etmek için bekliyor."
    override fun winbackTitle() = "Size bir hediye bekliyor! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount ücretsiz mesaj. Hediyenizi almak için dokunun!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
