package com.lvsmsmch.aichat.user.database

import com.lvsmsmch.aichat.chat.network.LimitsResponse
import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toUserDbo
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UserNotFoundException
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateStatement
import org.jetbrains.exposed.sql.update

class UserRepository {

    suspend fun addUser(userDbo: UserDbo) {
        dbQuery { Tables.Users.insert { it.from(userDbo) } }
    }

    suspend fun addUser(session: DbSession, userDbo: UserDbo) = addUser(userDbo)

    suspend fun getUserById(userId: String): UserDbo? = dbQuery {
        Tables.Users.selectAll()
            .where { Tables.Users.id eq userId }
            .limit(1)
            .firstOrNull()
            ?.toUserDbo()
    }

    suspend fun getUserById(session: DbSession, userId: String): UserDbo? = getUserById(userId)

    private suspend fun findOneBy(column: Column<String?>, value: String): UserDbo? = dbQuery {
        Tables.Users.selectAll()
            .where { column eq value }
            .limit(1)
            .firstOrNull()
            ?.toUserDbo()
    }

    suspend fun findByUsername(username: String): UserDbo? = dbQuery {
        Tables.Users.selectAll()
            .where { Tables.Users.username eq username }
            .limit(1)
            .firstOrNull()
            ?.toUserDbo()
    }

    suspend fun findUserByEmail(email: String): UserDbo? = findOneBy(Tables.Users.email, email)

    suspend fun findByGoogleId(googleId: String): UserDbo? =
        findOneBy(Tables.Users.googleOauthId, googleId)

    suspend fun findByDeviceId(deviceId: String): UserDbo? =
        findOneBy(Tables.Users.deviceId, deviceId)

    suspend fun getActiveUsersSince(since: UtcTimestamp): List<UserDbo> = dbQuery {
        Tables.Users.selectAll()
            .where { Tables.Users.lastActiveAt greaterEq since.toString() }
            .map { it.toUserDbo() }
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

        return LimitsResponse(
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
            hasSubscription = user.hasSubscription,
        )
    }

    /** Точечное обновление одного юзера. */
    private suspend fun updateById(userId: String, body: (UpdateStatement) -> Unit): Int = dbQuery {
        Tables.Users.update({ Tables.Users.id eq userId }) { body(it) }
    }

    suspend fun updateUser(
        session: DbSession,
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
        val nothingToDo = email == null && username == null && name == null && bio == null &&
            profilePictureUrl == null && profilePictureUrlThumbnail == null &&
            hashedPassword == null && color == null && removePicture != true
        if (nothingToDo) return
        updateById(userId) { statement ->
            email?.let { statement[Tables.Users.email] = it }
            username?.let { statement[Tables.Users.username] = it }
            name?.let { statement[Tables.Users.name] = it }
            bio?.let { statement[Tables.Users.bio] = it }
            profilePictureUrl?.let { statement[Tables.Users.profilePictureUrl] = it }
            profilePictureUrlThumbnail?.let {
                statement[Tables.Users.profilePictureUrlThumbnail] = it
            }
            hashedPassword?.let { statement[Tables.Users.hashedPassword] = it }
            color?.let { statement[Tables.Users.color] = it }
            if (removePicture == true) {
                statement[Tables.Users.profilePictureUrl] = null
                statement[Tables.Users.profilePictureUrlThumbnail] = null
            }
        }
    }

    suspend fun linkGoogleToUser(userId: String, googleId: String, email: String? = null) {
        updateById(userId) {
            it[Tables.Users.accountType] = AccountType.REGISTERED.name
            it[Tables.Users.deviceId] = null
            it[Tables.Users.googleOauthId] = googleId
            it[Tables.Users.email] = email
        }
    }

    suspend fun updateSubscriptionStatus(userId: String, hasSubscription: Boolean) {
        updateById(userId) {
            it[Tables.Users.hasSubscription] = hasSubscription
            // Первая подписка сжигает бесплатный триал навсегда
            if (hasSubscription) it[Tables.Users.trialUsed] = true
        }
    }

    suspend fun incrementFollowingCount(session: DbSession, userId: String, increment: Int) {
        updateById(userId) {
            it[Tables.Users.followingCount] = Tables.Users.followingCount plus increment
        }
    }

    suspend fun incrementFollowerCount(session: DbSession, userId: String, increment: Int) {
        updateById(userId) {
            it[Tables.Users.followerCount] = Tables.Users.followerCount plus increment
        }
    }

    suspend fun incrementFollowerCountForUsers(
        session: DbSession,
        userIds: List<String>,
        increment: Int,
    ) {
        if (userIds.isEmpty()) return
        dbQuery {
            Tables.Users.update({ Tables.Users.id inList userIds }) {
                it[Tables.Users.followerCount] = Tables.Users.followerCount plus increment
            }
        }
    }

    suspend fun incrementFollowingCountForUsers(
        session: DbSession,
        userIds: List<String>,
        increment: Int,
    ) {
        if (userIds.isEmpty()) return
        dbQuery {
            Tables.Users.update({ Tables.Users.id inList userIds }) {
                it[Tables.Users.followingCount] = Tables.Users.followingCount plus increment
            }
        }
    }

    suspend fun incrementPublicCharacterCount(session: DbSession, userId: String, increment: Int) {
        updateById(userId) {
            it[Tables.Users.publicCharacterCount] = Tables.Users.publicCharacterCount plus increment
        }
    }

    suspend fun incrementPrivateCharacterCount(session: DbSession, userId: String, increment: Int) {
        updateById(userId) {
            it[Tables.Users.privateCharacterCount] =
                Tables.Users.privateCharacterCount plus increment
        }
    }

    suspend fun resetHourlyCountersForAllUsers() {
        dbQuery {
            Tables.Users.update({ Tables.Users.hourlyMessageCount greater 0 }) {
                it[Tables.Users.hourlyMessageCount] = 0
            }
        }
    }

    suspend fun resetDailyCountersForAllUsers() {
        dbQuery {
            Tables.Users.update({
                (Tables.Users.dailyMessageCount greater 0) or (Tables.Users.dailyImageCount greater 0)
            }) {
                it[Tables.Users.dailyMessageCount] = 0
                it[Tables.Users.dailyImageCount] = 0
            }
        }
    }

    /** [DEBUG] Полный сброс лимитов юзера (дебаг-кнопка в настройках приложения). */
    suspend fun debugResetLimits(userId: String) {
        updateById(userId) {
            it[Tables.Users.hourlyMessageCount] = 0
            it[Tables.Users.dailyMessageCount] = 0
            it[Tables.Users.dailyImageCount] = 0
            it[Tables.Users.monthlyMessageCount] = 0
            it[Tables.Users.monthlyTopModelCount] = 0
            it[Tables.Users.monthlyTopImageCount] = 0
        }
    }

    /** Изображение сгенерировано (или зацензурено — тоже трата лимита). */
    suspend fun incrementImageCount(userId: String, onTopModel: Boolean) {
        updateById(userId) {
            it[Tables.Users.dailyImageCount] = Tables.Users.dailyImageCount plus 1
            if (onTopModel) {
                it[Tables.Users.monthlyTopImageCount] = Tables.Users.monthlyTopImageCount plus 1
            }
        }
    }

    // ---- Пуши ----

    suspend fun saveFcmToken(userId: String, token: String) {
        updateById(userId) { it[Tables.Users.fcmToken] = token }
    }

    /**
     * Кандидаты на пуш «лимиты возобновились»: фри-юзеры с токеном, выжегшие
     * дневной лимит (вызывать ДО полуночного сброса счётчиков).
     */
    suspend fun findLimitResetPushCandidates(): List<UserDbo> = dbQuery {
        Tables.Users.selectAll()
            .where {
                (Tables.Users.hasSubscription eq false) and
                    (Tables.Users.dailyMessageCount greaterEq DAILY_LIMIT_MESSAGES_REGULAR) and
                    Tables.Users.fcmToken.isNotNull()
            }
            .map { it.toUserDbo() }
    }

    suspend fun markLimitPushSent(userId: String, newStage: Int) {
        updateById(userId) {
            it[Tables.Users.limitPushStage] = newStage
            it[Tables.Users.lastLimitPushAt] = UtcTimestamp.now().toString()
        }
    }

    /**
     * Кандидаты на винбэк: писали хотя бы [minMessages] сообщений, не заходили
     * с [inactiveSinceIso], пуш не отправлялся с [lastPushBeforeIso] (или вообще).
     */
    suspend fun findWinbackCandidates(
        inactiveSinceIso: String,
        lastPushBeforeIso: String,
        minMessages: Int,
    ): List<UserDbo> = dbQuery {
        Tables.Users.selectAll()
            .where {
                (Tables.Users.hasSubscription eq false) and
                    (Tables.Users.totalMessagesCount greater minMessages) and
                    Tables.Users.fcmToken.isNotNull() and
                    (Tables.Users.lastActiveAt less inactiveSinceIso) and
                    (
                        Tables.Users.lastWinbackPushAt.isNull() or
                            (Tables.Users.lastWinbackPushAt less lastPushBeforeIso)
                        )
            }
            .map { it.toUserDbo() }
    }

    /** Винбэк-подарок: +[extra] сообщений и отметка отправки пуша. */
    suspend fun grantWinbackGift(userId: String, extra: Int) {
        updateById(userId) {
            it[Tables.Users.extraFreeMessagesCount] = Tables.Users.extraFreeMessagesCount plus extra
            it[Tables.Users.lastWinbackPushAt] = UtcTimestamp.now().toString()
        }
    }

    /** 1-го числа: месячные счётчики умного даунгрейда модели обнуляются. */
    suspend fun resetMonthlyCountersForAllUsers() {
        dbQuery {
            Tables.Users.update({
                (Tables.Users.monthlyMessageCount greater 0) or
                    (Tables.Users.monthlyTopImageCount greater 0)
            }) {
                it[Tables.Users.monthlyMessageCount] = 0
                it[Tables.Users.monthlyTopModelCount] = 0
                it[Tables.Users.monthlyTopImageCount] = 0
            }
        }
    }

    suspend fun notifyCharacterMessageWasSent(session: DbSession, userId: String) {
        val userDbo = getUserById(userId) ?: return
        // Сначала тратится обычный дневной лимит, экстра-подарок — только после него
        val dailyLimit =
            if (userDbo.hasSubscription) DAILY_LIMIT_MESSAGES_PREMIUM else DAILY_LIMIT_MESSAGES_REGULAR
        val spendExtra = !userDbo.hasSubscription &&
            userDbo.dailyMessageCount >= dailyLimit &&
            userDbo.extraFreeMessagesCount > 0
        // Тир считается ДО инкремента: топовый месячный счётчик растёт только
        // если это сообщение реально уйдёт топ-модели
        val onTopTier = ModelTierPicker.pick(userDbo) == ModelTierPicker.Tier.TOP

        updateById(userId) {
            if (spendExtra) {
                it[Tables.Users.extraFreeMessagesCount] =
                    Tables.Users.extraFreeMessagesCount plus -1
            } else {
                it[Tables.Users.hourlyMessageCount] = Tables.Users.hourlyMessageCount plus 1
                it[Tables.Users.dailyMessageCount] = Tables.Users.dailyMessageCount plus 1
            }
            it[Tables.Users.monthlyMessageCount] = Tables.Users.monthlyMessageCount plus 1
            if (onTopTier) {
                it[Tables.Users.monthlyTopModelCount] = Tables.Users.monthlyTopModelCount plus 1
            }
            it[Tables.Users.totalMessagesCount] = Tables.Users.totalMessagesCount plus 1
            it[Tables.Users.lastActiveAt] = UtcTimestamp.now().toString()
        }
    }

    suspend fun notifyChatWasCreated(session: DbSession, userId: String) {
        updateById(userId) {
            it[Tables.Users.totalChatsCount] = Tables.Users.totalChatsCount plus 1
            it[Tables.Users.lastActiveAt] = UtcTimestamp.now().toString()
        }
    }

    suspend fun addUserLimitsAfterRewardedWasWatched(userId: String) {
        updateById(userId) {
            it[Tables.Users.extraFreeMessagesCount] =
                Tables.Users.extraFreeMessagesCount plus EXTRA_AMOUNT_FOR_REWARD
        }
    }

    suspend fun setCharacterLanguage(userId: String, lang: String) {
        updateById(userId) { it[Tables.Users.characterLanguage] = lang }
    }

    suspend fun deleteUser(session: DbSession, userId: String): Boolean = dbQuery {
        Tables.Users.deleteWhere { Tables.Users.id eq userId } > 0
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
