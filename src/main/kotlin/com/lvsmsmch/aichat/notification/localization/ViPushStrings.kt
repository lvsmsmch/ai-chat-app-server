package com.lvsmsmch.aichat.notification

internal object ViPushStrings : PushStringTable {
    override fun newFollowerTitle() = "Người theo dõi mới! 🎉"
    override fun newFollowerBody(username: String) = "@$username bắt đầu theo dõi bạn"
    override fun newCommentTitle(characterName: String) = "Bình luận mới trên $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "Cột mốc! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName đã đạt ${formatMilestone(milestone)} tin nhắn"
    override fun dailyMessagesTitle() = "Tin nhắn hàng ngày của bạn đã trở lại! ✨"
    override fun dailyMessagesBody() = "Giới hạn đã được đặt lại - các nhân vật của bạn đang chờ trò chuyện."
    override fun winbackTitle() = "Một món quà đang chờ bạn! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount tin nhắn miễn phí. Chạm để nhận quà của bạn!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
