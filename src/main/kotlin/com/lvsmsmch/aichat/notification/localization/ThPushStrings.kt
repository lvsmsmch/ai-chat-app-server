package com.lvsmsmch.aichat.notification

internal object ThPushStrings : PushStringTable {
    override fun newFollowerTitle() = "ผู้ติดตามใหม่! 🎉"
    override fun newFollowerBody(username: String) = "@$username เริ่มติดตามคุณ"
    override fun newCommentTitle(characterName: String) = "ความคิดเห็นใหม่ใน $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "ถึงหลักไมล์! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName ถึง ${formatMilestone(milestone)} ข้อความ"
    override fun dailyMessagesTitle() = "ข้อความรายวันของคุณกลับมาแล้ว! ✨"
    override fun dailyMessagesBody() = "ขีดจำกัดรีเซ็ตแล้ว - ตัวละครของคุณกำลังรอแชทอยู่"
    override fun winbackTitle() = "มีของขวัญรอคุณอยู่! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount ข้อความฟรี แตะเพื่อรับของขวัญของคุณ!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000} ล้าน" else "$value"
}
