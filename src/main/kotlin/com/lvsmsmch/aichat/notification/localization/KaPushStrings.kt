package com.lvsmsmch.aichat.notification

internal object KaPushStrings : PushStringTable {
    override fun newFollowerTitle() = "ახალი გამომწერი! 🎉"
    override fun newFollowerBody(username: String) = "@$username გამოგყვებათ"
    override fun newCommentTitle(characterName: String) = "ახალი კომენტარი $characterName-ზე 💬"
    override fun newCommentBody(username: String, comment: String) = "@$username: $comment"
    override fun milestoneTitle() = "ეტაპი! 🎉"
    override fun milestoneBody(characterName: String, milestone: Int) =
        "$characterName-მა მიაღწია ${formatMilestone(milestone)} შეტყობინებას"
    override fun dailyMessagesTitle() = "თქვენი დღიური შეტყობინებები დაბრუნდა! ✨"
    override fun dailyMessagesBody() = "ლიმიტი გადატვირთულია - თქვენი პერსონაჟები ელოდებიან სასაუბროდ."
    override fun winbackTitle() = "საჩუქარი გელოდებათ! 🎁"
    override fun winbackBody(messageCount: Int) =
        "$messageCount უფასო შეტყობინება. შეეხეთ თქვენი საჩუქრის მისაღებად!"

    private fun formatMilestone(value: Int): String =
        if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000},000" else "$value"
}
