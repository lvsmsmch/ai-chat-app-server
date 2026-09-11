package com.lvsmsmch.aichat.notification

internal object ZhTwPushStrings : PushStringTable {
    override fun newFollowerTitle() = "新追蹤者！🎉"
    override fun newFollowerBody(username: String) = "@$username 開始追蹤您"
    override fun newCommentTitle(characterName: String) = "$characterName 有新留言 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username：$comment"
    override fun milestoneTitle() = "里程碑！🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName 已達 ${formatMilestone(milestone)} 則訊息"
    override fun dailyMessagesTitle() = "您的每日訊息回來了！✨"
    override fun dailyMessagesBody() = "額度已重設 - 您的角色們正等著您聊天。"
    override fun winbackTitle() = "一份禮物正等著您！🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount 個免費訊息。點擊領取您的禮物！"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000}百萬" else "$value"
}
