package com.lvsmsmch.aichat.notification

internal object ZhCnPushStrings : PushStringTable {
    override fun newFollowerTitle() = "新关注者！🎉"
    override fun newFollowerBody(username: String) = "@$username 开始关注你了"
    override fun newCommentTitle(characterName: String) = "$characterName 的新评论 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "里程碑！🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName 已达到 ${formatMilestone(milestone)} 条消息"
    override fun dailyMessagesTitle() = "你的每日消息已重置！✨"
    override fun dailyMessagesBody() = "额度已重置 - 你的角色们在等你聊天。"
    override fun winbackTitle() = "一份礼物正等着你！🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount 条免费消息。点击领取你的礼物！"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000}百万" else "$value"
}
