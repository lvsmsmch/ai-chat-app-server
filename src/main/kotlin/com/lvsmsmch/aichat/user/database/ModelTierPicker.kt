package com.lvsmsmch.aichat.user.database

/**
 * Умный даунгрейд модели вместо жёстких лимитов.
 *
 * Фри: первые [FREE_MONTHLY_TOP_LIMIT] сообщений в месяц — топ-модель,
 * дальше до конца месяца — средняя.
 * Премиум: без лимитов сообщений, но после [PREMIUM_DAILY_MID_THRESHOLD] в день —
 * средняя, после [PREMIUM_DAILY_LOW_THRESHOLD] в день — дешёвая; израсходовал
 * [PREMIUM_MONTHLY_TOP_LIMIT] сообщений топ-модели за месяц — топ до конца месяца
 * недоступна.
 */
object ModelTierPicker {

    enum class Tier { TOP, MID, LOW }

    const val FREE_MONTHLY_TOP_LIMIT = 500
    const val PREMIUM_DAILY_MID_THRESHOLD = 200
    const val PREMIUM_DAILY_LOW_THRESHOLD = 500
    const val PREMIUM_MONTHLY_TOP_LIMIT = 1000

    fun pick(user: UserDbo): Tier = if (user.hasSubscription) {
        when {
            user.dailyMessageCount >= PREMIUM_DAILY_LOW_THRESHOLD -> Tier.LOW
            user.dailyMessageCount >= PREMIUM_DAILY_MID_THRESHOLD -> Tier.MID
            user.monthlyTopModelCount >= PREMIUM_MONTHLY_TOP_LIMIT -> Tier.MID
            else -> Tier.TOP
        }
    } else {
        if (user.monthlyMessageCount >= FREE_MONTHLY_TOP_LIMIT) Tier.MID else Tier.TOP
    }
}
