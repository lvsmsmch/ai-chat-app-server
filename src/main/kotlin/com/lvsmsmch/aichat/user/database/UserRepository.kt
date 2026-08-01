package com.lvsmsmch.aichat.user.database

import com.lvsmsmch.aichat.chat.network.LimitsResponse
import com.lvsmsmch.aichat.utils.*
import com.mongodb.client.model.Updates
import com.mongodb.reactivestreams.client.ClientSession
import org.bson.conversions.Bson
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection


class UserRepository(
    private val collection: CoroutineCollection<UserDbo>
) {




    suspend fun ensureIndexes() {
        collection.ensureIndex(ascending(UserDbo::email))
        collection.ensureIndex(ascending(UserDbo::googleOauthId))
        collection.ensureIndex(ascending(UserDbo::facebookOauthId))
        collection.ensureIndex(ascending(UserDbo::username))
        collection.ensureIndex(ascending(UserDbo::lastActiveAt))
        collection.ensureIndex(ascending(UserDbo::deviceId))
    }



    val databaseEventsFlow = createDatabaseEventsFlow(collection)



    suspend fun addUser(userDbo: UserDbo) {
        collection.insertOne(userDbo)
    }

    suspend fun addUser(session: ClientSession, userDbo: UserDbo) {
        collection.insertOne(session, userDbo)
    }


    suspend fun getUserById(userId: String): UserDbo? {
        return collection.findOneById(userId)
    }

    suspend fun getUserById(session: ClientSession, userId: String): UserDbo? {
        return collection.findOneById(userId, session)
    }


    suspend fun findByUsername(username: String): UserDbo? {
        return collection.findOne(UserDbo::username eq username)
    }

    suspend fun findUserByEmail(email: String): UserDbo? {
        return collection.findOne(UserDbo::email eq email)
    }

    suspend fun findByGoogleId(googleId: String): UserDbo? {
        return collection.findOne(UserDbo::googleOauthId eq googleId)
    }

    suspend fun findByDeviceId(deviceId: String): UserDbo? {
        return collection.findOne(UserDbo::deviceId eq deviceId)
    }

    suspend fun getActiveUsersSince(since: UtcTimestamp): List<UserDbo> {
        return collection.find(
            UserDbo::lastActiveAt gte since.toString()
        ).toList()
    }

    suspend fun getLimits(userId: String): LimitsResponse {
        val user = getUserById(userId) ?: throw UserNotFoundException()

        val dailyLimit = if (user.hasSubscription) DAILY_LIMIT_MESSAGES_PREMIUM else DAILY_LIMIT_MESSAGES_REGULAR
        val hourlyLimit = if (user.hasSubscription) HOURLY_LIMIT_MESSAGES_PREMIUM else HOURLY_LIMIT_MESSAGES_REGULAR

        val now = java.time.LocalDateTime.now()

        // Премиум без лимитов вообще — вместо них после порогов даунгрейдится модель
        val limitUntil = if (user.hasSubscription || user.extraFreeMessagesCount > 0) {
            null
        } else if (user.dailyMessageCount >= dailyLimit) {
            val nextDay = now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
            UtcTimestamp(nextDay.toInstant(java.time.ZoneOffset.UTC)).toString()
        } else if (user.hourlyMessageCount >= hourlyLimit) {
            val nextHour = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
            UtcTimestamp(nextHour.toInstant(java.time.ZoneOffset.UTC)).toString()
        } else {
            null
        }

        val limitsResponse = LimitsResponse(
            limitUntil = limitUntil,
            hourlyUsed = user.hourlyMessageCount,
            hourlyLimit = hourlyLimit,
            dailyUsed = user.dailyMessageCount,
            dailyLimit = dailyLimit,
            extraLeft = user.extraFreeMessagesCount,
            extraAmountForReward = EXTRA_AMOUNT_FOR_REWARD,
            trialUsed = user.trialUsed,
            imagesUsed = user.dailyImageCount,
            imagesLimit = if (user.hasSubscription) DAILY_IMAGES_PREMIUM else 0,
            hasSubscription = user.hasSubscription
        )

        return limitsResponse
    }

    suspend fun updateUser(
        session: ClientSession,
        userId: String,
        email: String? = null,
        username: String? = null,
        name: String? = null,
        bio: String? = null,
        profilePictureUrl: String? = null,
        profilePictureUrlThumbnail: String? = null,
        removePicture: Boolean? = null,
        hashedPassword: String? = null,
        color: String? = null,
    ) {
        collection.findOneById(userId) ?: return
        val updates = mutableListOf<Bson>()
        email?.let { updates.add(setValue(UserDbo::email, it)) }
        username?.let { updates.add(setValue(UserDbo::username, it)) }
        name?.let { updates.add(setValue(UserDbo::name, it)) }
        bio?.let { updates.add(setValue(UserDbo::bio, it)) }
        profilePictureUrl?.let { updates.add(setValue(UserDbo::profilePictureUrl, it)) }
        profilePictureUrlThumbnail?.let { updates.add(setValue(UserDbo::profilePictureUrlThumbnail, it)) }
        hashedPassword?.let { updates.add(setValue(UserDbo::hashedPassword, it)) }
        color?.let { updates.add(setValue(UserDbo::color, it)) }
        removePicture?.let {
            if (it) {
                updates.add(setValue(UserDbo::profilePictureUrl, null))
                updates.add(setValue(UserDbo::profilePictureUrlThumbnail, null))
            }
        }
        if (updates.isEmpty()) return
        collection.updateOneById(session, userId, combine(*updates.toTypedArray()))
    }

    suspend fun linkGoogleToUser(
        userId: String,
        googleId: String,
        email: String? = null,
    ) {
        collection.findOneById(userId) ?: return
        collection.updateOneById(
            userId, combine(
                Updates.set(UserDbo::accountType.name, AccountType.REGISTERED),
                Updates.set(UserDbo::deviceId.name, null),
                Updates.set(UserDbo::googleOauthId.name, googleId),
                Updates.set(UserDbo::email.name, email),
            )
        )
    }

    suspend fun updateSubscriptionStatus(userId: String, hasSubscription: Boolean) {
        collection.updateOneById(
            userId, combine(
                Updates.set(UserDbo::hasSubscription.name, hasSubscription),
                // Первая подписка сжигает бесплатный триал навсегда
                *(if (hasSubscription) arrayOf(Updates.set(UserDbo::trialUsed.name, true)) else emptyArray()),
            )
        )
    }

    suspend fun incrementFollowingCount(session: ClientSession, userId: String, increment: Int) {
        collection.updateOneById(
            session,
            userId,
            inc(UserDbo::followingCount, increment)
        )
    }

    suspend fun incrementFollowerCount(session: ClientSession, userId: String, increment: Int) {
        collection.updateOneById(
            session,
            userId,
            inc(UserDbo::followerCount, increment)
        )
    }

    suspend fun incrementFollowerCountForUsers(session: ClientSession, userIds: List<String>, increment: Int) {
        collection.updateMany(
            session,
            UserDbo::id `in` userIds,
            inc(UserDbo::followerCount, increment)
        )
    }

    suspend fun incrementFollowingCountForUsers(session: ClientSession, userIds: List<String>, increment: Int) {
        collection.updateMany(
            session,
            UserDbo::id `in` userIds,
            inc(UserDbo::followingCount, increment)
        )
    }

    suspend fun incrementPublicCharacterCount(session: ClientSession, userId: String, increment: Int) {
        collection.updateOneById(
            session,
            userId,
            inc(UserDbo::publicCharacterCount, increment)
        )
    }

    suspend fun incrementPrivateCharacterCount(session: ClientSession, userId: String, increment: Int) {
        collection.updateOneById(
            session,
            userId,
            inc(UserDbo::privateCharacterCount, increment)
        )
    }

    suspend fun resetHourlyCountersForAllUsers() {
        collection.updateMany(
            filter = UserDbo::hourlyMessageCount gt 0,
            update = setValue(UserDbo::hourlyMessageCount, 0)
        )
    }

    suspend fun resetDailyCountersForAllUsers() {
        collection.updateMany(
            filter = or(UserDbo::dailyMessageCount gt 0, UserDbo::dailyImageCount gt 0),
            update = combine(
                setValue(UserDbo::dailyMessageCount, 0),
                setValue(UserDbo::dailyImageCount, 0),
            )
        )
    }

    /** [DEBUG] Полный сброс лимитов юзера (дебаг-кнопка в настройках приложения). */
    suspend fun debugResetLimits(userId: String) {
        collection.updateOneById(
            userId,
            combine(
                setValue(UserDbo::hourlyMessageCount, 0),
                setValue(UserDbo::dailyMessageCount, 0),
                setValue(UserDbo::dailyImageCount, 0),
                setValue(UserDbo::monthlyMessageCount, 0),
                setValue(UserDbo::monthlyTopModelCount, 0),
                setValue(UserDbo::monthlyTopImageCount, 0),
            )
        )
    }

    /** Изображение сгенерировано (или зацензурено — тоже трата лимита). */
    suspend fun incrementImageCount(userId: String, onTopModel: Boolean) {
        val updates = mutableListOf(inc(UserDbo::dailyImageCount, 1))
        if (onTopModel) updates.add(inc(UserDbo::monthlyTopImageCount, 1))
        collection.updateOneById(userId, combine(updates))
    }

    // ---- Пуши ----

    suspend fun saveFcmToken(userId: String, token: String) {
        collection.updateOneById(userId, setValue(UserDbo::fcmToken, token))
    }

    /**
     * Кандидаты на пуш «лимиты возобновились»: фри-юзеры с токеном, выжегшие
     * дневной лимит (вызывать ДО полуночного сброса счётчиков).
     */
    suspend fun findLimitResetPushCandidates(): List<UserDbo> =
        collection.find(
            and(
                UserDbo::hasSubscription eq false,
                UserDbo::dailyMessageCount gte DAILY_LIMIT_MESSAGES_REGULAR,
                UserDbo::fcmToken ne null,
            )
        ).toList()

    suspend fun markLimitPushSent(userId: String, newStage: Int) {
        collection.updateOneById(
            userId,
            combine(
                setValue(UserDbo::limitPushStage, newStage),
                setValue(UserDbo::lastLimitPushAt, UtcTimestamp.now().toString()),
            )
        )
    }

    /**
     * Кандидаты на винбэк: писали хотя бы [minMessages] сообщений, не заходили
     * с [inactiveSinceIso], пуш не отправлялся с [lastPushBeforeIso] (или вообще).
     */
    suspend fun findWinbackCandidates(inactiveSinceIso: String, lastPushBeforeIso: String, minMessages: Int): List<UserDbo> =
        collection.find(
            and(
                UserDbo::hasSubscription eq false,
                UserDbo::totalMessagesCount gt minMessages,
                UserDbo::fcmToken ne null,
                UserDbo::lastActiveAt lt inactiveSinceIso,
                or(
                    UserDbo::lastWinbackPushAt eq null,
                    UserDbo::lastWinbackPushAt lt lastPushBeforeIso,
                ),
            )
        ).toList()

    /** Винбэк-подарок: +[extra] сообщений и отметка отправки пуша. */
    suspend fun grantWinbackGift(userId: String, extra: Int) {
        collection.updateOneById(
            userId,
            combine(
                inc(UserDbo::extraFreeMessagesCount, extra),
                setValue(UserDbo::lastWinbackPushAt, UtcTimestamp.now().toString()),
            )
        )
    }

    /** 1-го числа: месячные счётчики умного даунгрейда модели обнуляются. */
    suspend fun resetMonthlyCountersForAllUsers() {
        collection.updateMany(
            filter = or(UserDbo::monthlyMessageCount gt 0, UserDbo::monthlyTopImageCount gt 0),
            update = combine(
                setValue(UserDbo::monthlyMessageCount, 0),
                setValue(UserDbo::monthlyTopModelCount, 0),
                setValue(UserDbo::monthlyTopImageCount, 0),
            )
        )
    }

    suspend fun notifyCharacterMessageWasSent(session: ClientSession, userId: String) {
        val userDbo = getUserById(session, userId) ?: return
        // Сначала тратится обычный дневной лимит, экстра-подарок — только после него
        val dailyLimit = if (userDbo.hasSubscription) DAILY_LIMIT_MESSAGES_PREMIUM else DAILY_LIMIT_MESSAGES_REGULAR
        val messagesUpdateBson = if (
            !userDbo.hasSubscription &&
            userDbo.dailyMessageCount >= dailyLimit &&
            userDbo.extraFreeMessagesCount > 0
        ) {
            inc(UserDbo::extraFreeMessagesCount, -1)
        } else {
            combine(
                inc(UserDbo::hourlyMessageCount, 1),
                inc(UserDbo::dailyMessageCount, 1),
            )
        }

        // Месячные счётчики умного даунгрейда: топовый инкрементим, только если
        // ЭТО сообщение реально уйдёт топ-модели (тир считается до инкремента)
        val monthlyUpdates = mutableListOf(inc(UserDbo::monthlyMessageCount, 1))
        if (ModelTierPicker.pick(userDbo) == ModelTierPicker.Tier.TOP) {
            monthlyUpdates.add(inc(UserDbo::monthlyTopModelCount, 1))
        }

        collection.updateOneById(
            session,
            userId,
            combine(
                messagesUpdateBson,
                combine(monthlyUpdates),
                inc(UserDbo::totalMessagesCount, 1),
                setValue(UserDbo::lastActiveAt, UtcTimestamp.now().toString())
            )
        )

    }

    suspend fun notifyChatWasCreated(session: ClientSession, userId: String) {
        collection.updateOneById(
            session,
            userId,
            combine(
                inc(UserDbo::totalChatsCount, 1),
                setValue(UserDbo::lastActiveAt, UtcTimestamp.now().toString())
            )
        )
    }

    suspend fun addUserLimitsAfterRewardedWasWatched(userId: String) {
        collection.updateOneById(
            userId,
            combine(
                inc(UserDbo::extraFreeMessagesCount, EXTRA_AMOUNT_FOR_REWARD)
            )
        )
    }


    suspend fun setCharacterLanguage(userId: String, lang: String) {
        collection.updateOneById(userId, org.litote.kmongo.setValue(UserDbo::characterLanguage, lang))
    }

    suspend fun deleteUser(session: ClientSession, userId: String): Boolean {
        val deleteResult = collection.deleteOneById(session, userId)
        return deleteResult.deletedCount > 0
    }


    companion object {
        const val DAILY_LIMIT_MESSAGES_REGULAR = 50
        // Часовой лимит у фри отсутствует: равен дневному и не срабатывает раньше него
        const val HOURLY_LIMIT_MESSAGES_REGULAR = 50
        // Премиум безлимитен (getLimits для подписки всегда отдаёт limitUntil=null);
        // значения нужны только для полей LimitsResponse
        const val DAILY_LIMIT_MESSAGES_PREMIUM = 1_000_000
        const val HOURLY_LIMIT_MESSAGES_PREMIUM = 1_000_000
        const val EXTRA_AMOUNT_FOR_REWARD = 5
        // Генерация изображений: фри — нельзя, премиум — 10/день.
        // Пока лимиты ВЫКЛЮЧЕНЫ для тестов: включаются env IMAGE_LIMITS_ENFORCED=true
        const val DAILY_IMAGES_PREMIUM = 10
        // Больше этого на топ-модели (Gemini) за месяц — дальше активный провайдер
        const val MONTHLY_TOP_IMAGES_LIMIT = 60
        val imageLimitsEnforced: Boolean
            get() = System.getenv("IMAGE_LIMITS_ENFORCED")?.toBoolean() ?: false
    }
}
