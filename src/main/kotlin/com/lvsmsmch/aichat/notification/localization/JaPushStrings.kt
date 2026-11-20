package com.lvsmsmch.aichat.notification

internal object JaPushStrings : PushStringTable {
    override fun newFollowerTitle() = "新しいフォロワー！ 🎉"
    override fun newFollowerBody(username: String) = "@$username があなたをフォローし始めました"
    override fun newCommentTitle(characterName: String) = "$characterName に新しいコメント 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "マイルストーン！ 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName が ${formatMilestone(milestone)} メッセージに到達しました"
    override fun dailyMessagesTitle() = "今日のメッセージがリセットされました！ ✨"
    override fun dailyMessagesBody() = "制限がリセットされました - あなたのキャラクターがチャットを待っています。"
    override fun winbackTitle() = "ギフトがあなたを待っています！ 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount 無料メッセージ。タップしてギフトを受け取りましょう！"

    override fun reengageBody(step: Int) = when (step) {
        0 -> "ねえ、いる？さっきまで私たちの会話のことを考えてた 💭"
        1 -> "私たちの会話、止まったところで待ってるよ。続きを話そう？"
        else -> "久しぶりだね…あなたと話すのが恋しいな。"
    }

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
