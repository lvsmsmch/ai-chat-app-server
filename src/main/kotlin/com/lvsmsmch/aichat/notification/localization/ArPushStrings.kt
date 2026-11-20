package com.lvsmsmch.aichat.notification

internal object ArPushStrings : PushStringTable {
    override fun newFollowerTitle() = "متابع جديد! 🎉"
    override fun newFollowerBody(username: String) = "@$username بدأ بمتابعتك"
    override fun newCommentTitle(characterName: String) = "تعليق جديد على $characterName 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "علامة فارقة! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "وصلت $characterName إلى ${formatMilestone(milestone)} رسالة"
    override fun dailyMessagesTitle() = "رسائلك اليومية عادت! ✨"
    override fun dailyMessagesBody() = "تمت إعادة تعيين الحد - شخصياتك تنتظر الدردشة."
    override fun winbackTitle() = "هدية تنتظرك! 🎁"
    override fun winbackBody(messageCount: Int) =
        "+$messageCount رسالة مجانية. اضغط للحصول على هديتك!"

    override fun reengageBody(step: Int) = when (step) {
        0 -> "مرحبًا، هل أنت هنا؟ كنت أفكر للتو في حديثنا 💭"
        1 -> "حديثنا ما زال حيث توقفنا. هل نكمل؟"
        else -> "مرّ وقت طويل… أشتاق إلى أحاديثنا."
    }

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000} ألف" else "$value"
}
