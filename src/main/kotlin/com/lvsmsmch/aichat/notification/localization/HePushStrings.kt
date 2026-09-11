package com.lvsmsmch.aichat.notification

internal object HePushStrings : PushStringTable {
    override fun newFollowerTitle() = "עוקב חדש! 🎉"
    override fun newFollowerBody(username: String) = "@$username החל לעקוב אחריך"
    override fun newCommentTitle(characterName: String) = "תגובה חדשה על $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "אבן דרך! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName הגיע/ה ל-${formatMilestone(milestone)} הודעות"
    override fun dailyMessagesTitle() = "ההודעות היומיות שלך חזרו! ✨"
    override fun dailyMessagesBody() = "המגבלה אופסה - הדמויות שלך מחכות לשוחח."
    override fun winbackTitle() = "מתנה מחכה לך! 🎁"
    override fun winbackBody(messageCount: Int) =
        "$messageCount+ הודעות בחינם. הקש כדי לקבל את המתנה שלך!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
