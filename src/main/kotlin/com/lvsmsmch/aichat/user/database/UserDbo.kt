package com.lvsmsmch.aichat.user.database

import com.lvsmsmch.aichat._common.AvatarColors
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class UserDbo(
    @BsonId val id: String,
    val createdAt: String = UtcTimestamp.now().toString(),
    val lastActiveAt: String = UtcTimestamp.now().toString(),
    val username: String,
    val name: String? = null,
    val profilePictureUrl: String? = null,
    val profilePictureUrlThumbnail: String? = null,
    val email: String? = null,
    val bio: String? = null,
    val googleOauthId: String? = null,
    val accountType: AccountType = AccountType.GUEST,
    val hasSubscription: Boolean = false,
    /** Язык персонажей (каталог, промпты, ответы в чате). */
    val characterLanguage: String = "en",
    val deviceId: String? = null,
    val facebookOauthId: String? = null,
    /** Apple sub: почту Apple отдаёт только при первой авторизации, id — всегда. */
    val appleOauthId: String? = null,
    val hashedPassword: String? = null,
    val privateCharacterCount: Int = 0,
    val publicCharacterCount: Int = 0,
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val hourlyMessageCount: Int = 0,
    val dailyMessageCount: Int = 0,
    // Месячные счётчики для умного даунгрейда модели (сброс 1-го числа):
    // все сообщения за месяц и сообщения, сгенерированные топ-моделью
    val monthlyMessageCount: Int = 0,
    val monthlyTopModelCount: Int = 0,
    // Сгенерированных изображений сегодня (сброс в полночь вместе с дневным)
    val dailyImageCount: Int = 0,
    // Изображений на ТОП-модели за месяц (сброс 1-го): после порога — даунгрейд
    val monthlyTopImageCount: Int = 0,
    val totalMessagesCount: Int = 0,
    val totalChatsCount: Int = 0,
    val extraFreeMessagesCount: Int = 0,
    // FCM-токен девайса для пушей (null — пуши недоступны)
    val fcmToken: String? = null,
    // Каскад пушей «лимиты возобновились»: 0 → сразу, 1 → через 3 дня, дальше — раз в неделю
    val limitPushStage: Int = 0,
    val lastLimitPushAt: String? = null,
    // Винбэк-подарок (+100 сообщений неактивным) — не чаще раза в 30 дней
    val lastWinbackPushAt: String? = null,
    val color: String = AvatarColors.random(),
    // Бесплатный триал одноразовый: выставляется при первой подписке
    val trialUsed: Boolean = false,
    /** Почта подтверждена кодом из письма (у аккаунтов Google — сразу true). */
    val emailVerified: Boolean = false
)