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
    val deviceId: String? = null,
    val facebookOauthId: String? = null,
    val hashedPassword: String? = null,
    val privateCharacterCount: Int = 0,
    val publicCharacterCount: Int = 0,
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val hourlyMessageCount: Int = 0,
    val dailyMessageCount: Int = 0,
    val totalMessagesCount: Int = 0,
    val totalChatsCount: Int = 0,
    val extraFreeMessagesCount: Int = 0,
    val color: String = AvatarColors.random(),
    // Бесплатный триал одноразовый: выставляется при первой подписке
    val trialUsed: Boolean = false
)