package com.lvsmsmch.aichat.db

import com.lvsmsmch.aichat._common.database.DeletedIdsStatsDbo
import com.lvsmsmch.aichat._common.database.ReportDbo
import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionDbo
import com.lvsmsmch.aichat.cache.database.CategoryRecommendationsCacheDbo
import com.lvsmsmch.aichat.cache.database.CharacterListCopyDbo
import com.lvsmsmch.aichat.cache.database.DefaultRecommendationsCacheDbo
import com.lvsmsmch.aichat.cache.database.DiscoverSectionDbo
import com.lvsmsmch.aichat.cache.database.DiscoverSectionsCacheDbo
import com.lvsmsmch.aichat.cache.database.UserRecommendationsCacheDbo
import com.lvsmsmch.aichat.character.database.CharacterActivityLogDbo
import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.character.database.CharacterLikeDbo
import com.lvsmsmch.aichat.character.database.CharacterTranslationDbo
import com.lvsmsmch.aichat.character.database.SearchSuggestionDbo
import com.lvsmsmch.aichat.chat.database.ChatDbo
import com.lvsmsmch.aichat.chat.database.ChatType
import com.lvsmsmch.aichat.chat.database.MessageDbo
import com.lvsmsmch.aichat.chat.database.MessageRatingDbo
import com.lvsmsmch.aichat.comment.database.CommentDbo
import com.lvsmsmch.aichat.comment.database.CommentLikeDbo
import com.lvsmsmch.aichat.feedback.database.FeedbackDbo
import com.lvsmsmch.aichat.notification.database.UserNotificationDbo
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.review.database.ReviewLikeDbo
import com.lvsmsmch.aichat.user.database.AccountType
import com.lvsmsmch.aichat.user.database.DeviceLimitCarryoverDbo
import com.lvsmsmch.aichat.user.database.FollowDbo
import com.lvsmsmch.aichat.user.database.UserBlockDbo
import com.lvsmsmch.aichat.user.database.UserDbo
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.statements.UpdateBuilder

/**
 * Перекладывание DBO ↔ строка таблицы. Ровно одно место на весь проект: и
 * репозитории, и одноразовая переливка из Mongo пользуются этими же функциями,
 * поэтому формат не может разъехаться между ними.
 */

// ---- JSON-поля (списки и мапы внутри документа) ----

private val stringListSerializer = ListSerializer(String.serializer())
private val floatMapSerializer = MapSerializer(String.serializer(), Float.serializer())
private val translationsSerializer =
    MapSerializer(String.serializer(), CharacterTranslationDbo.serializer())
private val sectionsSerializer = ListSerializer(DiscoverSectionDbo.serializer())

private fun List<String>.toJson(): String = Db.json.encodeToString(stringListSerializer, this)
private fun String.toStringList(): List<String> = Db.json.decodeFromString(stringListSerializer, this)

// Точечные обновления отдельных JSON-колонок (репозитории пишут не весь DBO)
fun encodeStringList(list: List<String>): String = list.toJson()

fun encodeFloatMap(map: Map<String, Float>): String =
    Db.json.encodeToString(floatMapSerializer, map)

fun encodeTranslations(map: Map<String, CharacterTranslationDbo>): String =
    Db.json.encodeToString(translationsSerializer, map)

// ---- users ----

fun ResultRow.toUserDbo(): UserDbo = with(Tables.Users) {
    UserDbo(
        id = this@toUserDbo[id],
        createdAt = this@toUserDbo[createdAt],
        lastActiveAt = this@toUserDbo[lastActiveAt],
        username = this@toUserDbo[username],
        name = this@toUserDbo[name],
        profilePictureUrl = this@toUserDbo[profilePictureUrl],
        profilePictureUrlThumbnail = this@toUserDbo[profilePictureUrlThumbnail],
        email = this@toUserDbo[email],
        bio = this@toUserDbo[bio],
        googleOauthId = this@toUserDbo[googleOauthId],
        accountType = AccountType.valueOf(this@toUserDbo[accountType]),
        hasSubscription = this@toUserDbo[hasSubscription],
        characterLanguage = this@toUserDbo[characterLanguage],
        deviceId = this@toUserDbo[deviceId],
        appleOauthId = this@toUserDbo[appleOauthId],
        hashedPassword = this@toUserDbo[hashedPassword],
        privateCharacterCount = this@toUserDbo[privateCharacterCount],
        publicCharacterCount = this@toUserDbo[publicCharacterCount],
        followerCount = this@toUserDbo[followerCount],
        followingCount = this@toUserDbo[followingCount],
        hourlyMessageCount = this@toUserDbo[hourlyMessageCount],
        dailyMessageCount = this@toUserDbo[dailyMessageCount],
        monthlyMessageCount = this@toUserDbo[monthlyMessageCount],
        monthlyTopModelCount = this@toUserDbo[monthlyTopModelCount],
        dailyImageCount = this@toUserDbo[dailyImageCount],
        monthlyTopImageCount = this@toUserDbo[monthlyTopImageCount],
        totalMessagesCount = this@toUserDbo[totalMessagesCount],
        totalChatsCount = this@toUserDbo[totalChatsCount],
        extraFreeMessagesCount = this@toUserDbo[extraFreeMessagesCount],
        fcmToken = this@toUserDbo[fcmToken],
        limitPushStage = this@toUserDbo[limitPushStage],
        lastLimitPushAt = this@toUserDbo[lastLimitPushAt],
        lastWinbackPushAt = this@toUserDbo[lastWinbackPushAt],
        color = this@toUserDbo[color],
        trialUsed = this@toUserDbo[trialUsed],
        emailVerified = this@toUserDbo[emailVerified],
    )
}

fun UpdateBuilder<*>.from(dbo: UserDbo) = with(Tables.Users) {
    this@from[id] = dbo.id
    this@from[createdAt] = dbo.createdAt
    this@from[lastActiveAt] = dbo.lastActiveAt
    this@from[username] = dbo.username
    this@from[name] = dbo.name
    this@from[profilePictureUrl] = dbo.profilePictureUrl
    this@from[profilePictureUrlThumbnail] = dbo.profilePictureUrlThumbnail
    this@from[email] = dbo.email
    this@from[bio] = dbo.bio
    this@from[googleOauthId] = dbo.googleOauthId
    this@from[accountType] = dbo.accountType.name
    this@from[hasSubscription] = dbo.hasSubscription
    this@from[characterLanguage] = dbo.characterLanguage
    this@from[deviceId] = dbo.deviceId
    this@from[appleOauthId] = dbo.appleOauthId
    this@from[hashedPassword] = dbo.hashedPassword
    this@from[privateCharacterCount] = dbo.privateCharacterCount
    this@from[publicCharacterCount] = dbo.publicCharacterCount
    this@from[followerCount] = dbo.followerCount
    this@from[followingCount] = dbo.followingCount
    this@from[hourlyMessageCount] = dbo.hourlyMessageCount
    this@from[dailyMessageCount] = dbo.dailyMessageCount
    this@from[monthlyMessageCount] = dbo.monthlyMessageCount
    this@from[monthlyTopModelCount] = dbo.monthlyTopModelCount
    this@from[dailyImageCount] = dbo.dailyImageCount
    this@from[monthlyTopImageCount] = dbo.monthlyTopImageCount
    this@from[totalMessagesCount] = dbo.totalMessagesCount
    this@from[totalChatsCount] = dbo.totalChatsCount
    this@from[extraFreeMessagesCount] = dbo.extraFreeMessagesCount
    this@from[fcmToken] = dbo.fcmToken
    this@from[limitPushStage] = dbo.limitPushStage
    this@from[lastLimitPushAt] = dbo.lastLimitPushAt
    this@from[lastWinbackPushAt] = dbo.lastWinbackPushAt
    this@from[color] = dbo.color
    this@from[trialUsed] = dbo.trialUsed
    this@from[emailVerified] = dbo.emailVerified
}

// ---- characters ----

fun ResultRow.toCharacterDbo(): CharacterDbo = with(Tables.Characters) {
    CharacterDbo(
        id = this@toCharacterDbo[id],
        createdAt = this@toCharacterDbo[createdAt],
        authorId = this@toCharacterDbo[authorId],
        name = this@toCharacterDbo[name],
        description = this@toCharacterDbo[description],
        prompt = this@toCharacterDbo[prompt],
        initialMessage = this@toCharacterDbo[initialMessage],
        picUrl = this@toCharacterDbo[picUrl],
        picUrlThumbnail = this@toCharacterDbo[picUrlThumbnail],
        cover = this@toCharacterDbo[cover],
        visibility = this@toCharacterDbo[visibility],
        category = this@toCharacterDbo[category],
        tags = this@toCharacterDbo[tags].toStringList(),
        totalChats = this@toCharacterDbo[totalChats],
        totalMessages = this@toCharacterDbo[totalMessages],
        totalReviews = this@toCharacterDbo[totalReviews],
        totalComments = this@toCharacterDbo[totalComments],
        totalLikes = this@toCharacterDbo[totalLikes],
        averageRating = this@toCharacterDbo[averageRating],
        trendingScore = this@toCharacterDbo[trendingScore],
        trendingScoreUpdatedAt = this@toCharacterDbo[trendingScoreUpdatedAt],
        recommendationScore = this@toCharacterDbo[recommendationScore],
        recommendationScoreUpdatedAt = this@toCharacterDbo[recommendationScoreUpdatedAt],
        recommendationsScoreMultiplier = this@toCharacterDbo[recommendationsScoreMultiplier],
        coOccurrenceScore = Db.json.decodeFromString(
            floatMapSerializer, this@toCharacterDbo[coOccurrenceScore],
        ),
        coOccurrenceScoreUpdatedAt = this@toCharacterDbo[coOccurrenceScoreUpdatedAt],
        similarCharacterIds = this@toCharacterDbo[similarCharacterIds].toStringList(),
        similarCharactersUpdatedAt = this@toCharacterDbo[similarCharactersUpdatedAt],
        color = this@toCharacterDbo[color],
        topRank = this@toCharacterDbo[topRank],
        translations = Db.json.decodeFromString(
            translationsSerializer, this@toCharacterDbo[translations],
        ),
    )
}

fun UpdateBuilder<*>.from(dbo: CharacterDbo) = with(Tables.Characters) {
    this@from[id] = dbo.id
    this@from[createdAt] = dbo.createdAt
    this@from[authorId] = dbo.authorId
    this@from[name] = dbo.name
    this@from[description] = dbo.description
    this@from[prompt] = dbo.prompt
    this@from[initialMessage] = dbo.initialMessage
    this@from[picUrl] = dbo.picUrl
    this@from[picUrlThumbnail] = dbo.picUrlThumbnail
    this@from[cover] = dbo.cover
    this@from[visibility] = dbo.visibility
    this@from[category] = dbo.category
    this@from[tags] = dbo.tags.toJson()
    this@from[totalChats] = dbo.totalChats
    this@from[totalMessages] = dbo.totalMessages
    this@from[totalReviews] = dbo.totalReviews
    this@from[totalComments] = dbo.totalComments
    this@from[totalLikes] = dbo.totalLikes
    this@from[averageRating] = dbo.averageRating
    this@from[trendingScore] = dbo.trendingScore
    this@from[trendingScoreUpdatedAt] = dbo.trendingScoreUpdatedAt
    this@from[recommendationScore] = dbo.recommendationScore
    this@from[recommendationScoreUpdatedAt] = dbo.recommendationScoreUpdatedAt
    this@from[recommendationsScoreMultiplier] = dbo.recommendationsScoreMultiplier
    this@from[coOccurrenceScore] = Db.json.encodeToString(floatMapSerializer, dbo.coOccurrenceScore)
    this@from[coOccurrenceScoreUpdatedAt] = dbo.coOccurrenceScoreUpdatedAt
    this@from[similarCharacterIds] = dbo.similarCharacterIds.toJson()
    this@from[similarCharactersUpdatedAt] = dbo.similarCharactersUpdatedAt
    this@from[color] = dbo.color
    this@from[topRank] = dbo.topRank
    this@from[translations] = Db.json.encodeToString(translationsSerializer, dbo.translations)
}

// ---- chats ----

fun ResultRow.toChatDbo(): ChatDbo = with(Tables.Chats) {
    ChatDbo(
        id = this@toChatDbo[id],
        clientId = this@toChatDbo[clientId],
        lastModifiedAt = this@toChatDbo[lastModifiedAt],
        createdAt = this@toChatDbo[createdAt],
        type = ChatType.valueOf(this@toChatDbo[type]),
        userId = this@toChatDbo[userId],
        characterIds = this@toChatDbo[characterIds].toStringList(),
        isMuted = this@toChatDbo[isMuted],
        customName = this@toChatDbo[customName],
        cover = this@toChatDbo[cover],
        isFirstChatWithThisCharacter = this@toChatDbo[isFirstChatWithThisCharacter],
        isDeleted = this@toChatDbo[isDeleted],
        deletedAt = this@toChatDbo[deletedAt],
        color = this@toChatDbo[color],
    )
}

fun UpdateBuilder<*>.from(dbo: ChatDbo) = with(Tables.Chats) {
    this@from[id] = dbo.id
    this@from[clientId] = dbo.clientId
    this@from[lastModifiedAt] = dbo.lastModifiedAt
    this@from[createdAt] = dbo.createdAt
    this@from[type] = dbo.type.name
    this@from[userId] = dbo.userId
    this@from[characterIds] = dbo.characterIds.toJson()
    this@from[isMuted] = dbo.isMuted
    this@from[customName] = dbo.customName
    this@from[cover] = dbo.cover
    this@from[isFirstChatWithThisCharacter] = dbo.isFirstChatWithThisCharacter
    this@from[isDeleted] = dbo.isDeleted
    this@from[deletedAt] = dbo.deletedAt
    this@from[color] = dbo.color
}

// ---- messages ----

fun ResultRow.toMessageDbo(): MessageDbo = with(Tables.Messages) {
    MessageDbo(
        id = this@toMessageDbo[id],
        clientId = this@toMessageDbo[clientId],
        lastModifiedAt = this@toMessageDbo[lastModifiedAt],
        createdAt = this@toMessageDbo[createdAt],
        chatId = this@toMessageDbo[chatId],
        chatClientId = this@toMessageDbo[chatClientId],
        senderId = this@toMessageDbo[senderId],
        isSentByUser = this@toMessageDbo[isSentByUser],
        text = this@toMessageDbo[text],
        imageUrl = this@toMessageDbo[imageUrl],
        isImage = this@toMessageDbo[isImage],
        imageDebugInfo = this@toMessageDbo[imageDebugInfo],
        isRead = this@toMessageDbo[isRead],
        status = this@toMessageDbo[status],
        failReason = this@toMessageDbo[failReason],
        nsfw = this@toMessageDbo[nsfw],
        variants = this@toMessageDbo[variants].toStringList(),
        selectedVariant = this@toMessageDbo[selectedVariant],
        isDeleted = this@toMessageDbo[isDeleted],
        deletedAt = this@toMessageDbo[deletedAt],
    )
}

fun UpdateBuilder<*>.from(dbo: MessageDbo) = with(Tables.Messages) {
    this@from[id] = dbo.id
    this@from[clientId] = dbo.clientId
    this@from[lastModifiedAt] = dbo.lastModifiedAt
    this@from[createdAt] = dbo.createdAt
    this@from[chatId] = dbo.chatId
    this@from[chatClientId] = dbo.chatClientId
    this@from[senderId] = dbo.senderId
    this@from[isSentByUser] = dbo.isSentByUser
    this@from[text] = dbo.text
    this@from[imageUrl] = dbo.imageUrl
    this@from[isImage] = dbo.isImage
    this@from[imageDebugInfo] = dbo.imageDebugInfo
    this@from[isRead] = dbo.isRead
    this@from[status] = dbo.status
    this@from[failReason] = dbo.failReason
    this@from[nsfw] = dbo.nsfw
    this@from[variants] = dbo.variants.toJson()
    this@from[selectedVariant] = dbo.selectedVariant
    this@from[isDeleted] = dbo.isDeleted
    this@from[deletedAt] = dbo.deletedAt
}

// ---- sessions ----

fun ResultRow.toSessionDbo(): SessionDbo = with(Tables.Sessions) {
    SessionDbo(
        id = this@toSessionDbo[id],
        token = this@toSessionDbo[token],
        createdAt = this@toSessionDbo[createdAt],
        expiresAt = this@toSessionDbo[expiresAt],
        userId = this@toSessionDbo[userId],
        ipAddress = this@toSessionDbo[ipAddress],
    )
}

fun UpdateBuilder<*>.from(dbo: SessionDbo) = with(Tables.Sessions) {
    this@from[id] = dbo.id
    this@from[token] = dbo.token
    this@from[createdAt] = dbo.createdAt
    this@from[expiresAt] = dbo.expiresAt
    this@from[userId] = dbo.userId
    this@from[ipAddress] = dbo.ipAddress
}

// ---- comments ----

fun ResultRow.toCommentDbo(): CommentDbo = with(Tables.Comments) {
    CommentDbo(
        id = this@toCommentDbo[id],
        createdAt = this@toCommentDbo[createdAt],
        characterId = this@toCommentDbo[characterId],
        authorId = this@toCommentDbo[authorId],
        parentId = this@toCommentDbo[parentId],
        replyToUserId = this@toCommentDbo[replyToUserId],
        text = this@toCommentDbo[text],
        likesCount = this@toCommentDbo[likesCount],
        repliesCount = this@toCommentDbo[repliesCount],
        editedAt = this@toCommentDbo[editedAt],
    )
}

fun UpdateBuilder<*>.from(dbo: CommentDbo) = with(Tables.Comments) {
    this@from[id] = dbo.id
    this@from[createdAt] = dbo.createdAt
    this@from[characterId] = dbo.characterId
    this@from[authorId] = dbo.authorId
    this@from[parentId] = dbo.parentId
    this@from[replyToUserId] = dbo.replyToUserId
    this@from[text] = dbo.text
    this@from[likesCount] = dbo.likesCount
    this@from[repliesCount] = dbo.repliesCount
    this@from[editedAt] = dbo.editedAt
}

// ---- comment_likes ----

fun ResultRow.toCommentLikeDbo(): CommentLikeDbo = with(Tables.CommentLikes) {
    CommentLikeDbo(
        id = this@toCommentLikeDbo[id],
        userId = this@toCommentLikeDbo[userId],
        commentId = this@toCommentLikeDbo[commentId],
        likedAt = this@toCommentLikeDbo[likedAt],
    )
}

fun UpdateBuilder<*>.from(dbo: CommentLikeDbo) = with(Tables.CommentLikes) {
    this@from[id] = dbo.id
    this@from[userId] = dbo.userId
    this@from[commentId] = dbo.commentId
    this@from[likedAt] = dbo.likedAt
}

// ---- reviews ----

fun ResultRow.toReviewDbo(): ReviewDbo = with(Tables.Reviews) {
    ReviewDbo(
        id = this@toReviewDbo[id],
        createdAt = this@toReviewDbo[createdAt],
        characterId = this@toReviewDbo[characterId],
        isAnonymous = this@toReviewDbo[isAnonymous],
        authorId = this@toReviewDbo[authorId],
        rating = this@toReviewDbo[rating],
        text = this@toReviewDbo[text],
        likesCount = this@toReviewDbo[likesCount],
        editedAt = this@toReviewDbo[editedAt],
    )
}

fun UpdateBuilder<*>.from(dbo: ReviewDbo) = with(Tables.Reviews) {
    this@from[id] = dbo.id
    this@from[createdAt] = dbo.createdAt
    this@from[characterId] = dbo.characterId
    this@from[isAnonymous] = dbo.isAnonymous
    this@from[authorId] = dbo.authorId
    this@from[rating] = dbo.rating
    this@from[text] = dbo.text
    this@from[likesCount] = dbo.likesCount
    this@from[editedAt] = dbo.editedAt
}

// ---- review_likes ----

fun ResultRow.toReviewLikeDbo(): ReviewLikeDbo = with(Tables.ReviewLikes) {
    ReviewLikeDbo(
        id = this@toReviewLikeDbo[id],
        userId = this@toReviewLikeDbo[userId],
        reviewId = this@toReviewLikeDbo[reviewId],
        likedAt = this@toReviewLikeDbo[likedAt],
    )
}

fun UpdateBuilder<*>.from(dbo: ReviewLikeDbo) = with(Tables.ReviewLikes) {
    this@from[id] = dbo.id
    this@from[userId] = dbo.userId
    this@from[reviewId] = dbo.reviewId
    this@from[likedAt] = dbo.likedAt
}

// ---- character_likes ----

fun ResultRow.toCharacterLikeDbo(): CharacterLikeDbo = with(Tables.CharacterLikes) {
    CharacterLikeDbo(
        id = this@toCharacterLikeDbo[id],
        userId = this@toCharacterLikeDbo[userId],
        characterId = this@toCharacterLikeDbo[characterId],
        likedAt = this@toCharacterLikeDbo[likedAt],
    )
}

fun UpdateBuilder<*>.from(dbo: CharacterLikeDbo) = with(Tables.CharacterLikes) {
    this@from[id] = dbo.id
    this@from[userId] = dbo.userId
    this@from[characterId] = dbo.characterId
    this@from[likedAt] = dbo.likedAt
}

// ---- follows ----

fun ResultRow.toFollowDbo(): FollowDbo = with(Tables.Follows) {
    FollowDbo(
        id = this@toFollowDbo[id],
        followedAt = this@toFollowDbo[followedAt],
        followerId = this@toFollowDbo[followerId],
        followeeId = this@toFollowDbo[followeeId],
    )
}

fun UpdateBuilder<*>.from(dbo: FollowDbo) = with(Tables.Follows) {
    this@from[id] = dbo.id
    this@from[followedAt] = dbo.followedAt
    this@from[followerId] = dbo.followerId
    this@from[followeeId] = dbo.followeeId
}

// ---- user blocks ----

fun ResultRow.toUserBlockDbo(): UserBlockDbo = with(Tables.UserBlocks) {
    UserBlockDbo(
        id = this@toUserBlockDbo[id],
        blockedAt = this@toUserBlockDbo[blockedAt],
        blockerId = this@toUserBlockDbo[blockerId],
        blockedId = this@toUserBlockDbo[blockedId],
    )
}

fun UpdateBuilder<*>.from(dbo: UserBlockDbo) = with(Tables.UserBlocks) {
    this@from[id] = dbo.id
    this@from[blockedAt] = dbo.blockedAt
    this@from[blockerId] = dbo.blockerId
    this@from[blockedId] = dbo.blockedId
}

// ---- reports ----

fun ResultRow.toReportDbo(): ReportDbo = with(Tables.Reports) {
    ReportDbo(
        id = this@toReportDbo[id],
        reportedAt = this@toReportDbo[reportedAt],
        reportedBy = this@toReportDbo[reportedBy],
        entityType = this@toReportDbo[entityType],
        entityId = this@toReportDbo[entityId],
        reason = this@toReportDbo[reason],
        text = this@toReportDbo[text],
    )
}

fun UpdateBuilder<*>.from(dbo: ReportDbo) = with(Tables.Reports) {
    this@from[id] = dbo.id
    this@from[reportedAt] = dbo.reportedAt
    this@from[reportedBy] = dbo.reportedBy
    this@from[entityType] = dbo.entityType
    this@from[entityId] = dbo.entityId
    this@from[reason] = dbo.reason
    this@from[text] = dbo.text
}

// ---- message ratings ----

fun ResultRow.toMessageRatingDbo(): MessageRatingDbo = with(Tables.MessageRatings) {
    MessageRatingDbo(
        id = this@toMessageRatingDbo[id],
        createdAt = this@toMessageRatingDbo[createdAt],
        updatedAt = this@toMessageRatingDbo[updatedAt],
        rating = this@toMessageRatingDbo[rating],
        userId = this@toMessageRatingDbo[userId],
        userHasSubscription = this@toMessageRatingDbo[userHasSubscription],
        messageId = this@toMessageRatingDbo[messageId],
        messageClientId = this@toMessageRatingDbo[messageClientId],
        chatId = this@toMessageRatingDbo[chatId],
        chatType = this@toMessageRatingDbo[chatType],
        characterId = this@toMessageRatingDbo[characterId],
        characterName = this@toMessageRatingDbo[characterName],
        characterAuthorId = this@toMessageRatingDbo[characterAuthorId],
        characterCategory = this@toMessageRatingDbo[characterCategory],
        isImage = this@toMessageRatingDbo[isImage],
        messageText = this@toMessageRatingDbo[messageText],
        imageUrl = this@toMessageRatingDbo[imageUrl],
        variantIndex = this@toMessageRatingDbo[variantIndex],
        variantsCount = this@toMessageRatingDbo[variantsCount],
        model = this@toMessageRatingDbo[model],
        generationInfo = this@toMessageRatingDbo[generationInfo],
        nsfw = this@toMessageRatingDbo[nsfw],
        language = this@toMessageRatingDbo[language],
        messageCreatedAt = this@toMessageRatingDbo[messageCreatedAt],
    )
}

fun UpdateBuilder<*>.from(dbo: MessageRatingDbo) = with(Tables.MessageRatings) {
    this@from[id] = dbo.id
    this@from[createdAt] = dbo.createdAt
    this@from[updatedAt] = dbo.updatedAt
    this@from[rating] = dbo.rating
    this@from[userId] = dbo.userId
    this@from[userHasSubscription] = dbo.userHasSubscription
    this@from[messageId] = dbo.messageId
    this@from[messageClientId] = dbo.messageClientId
    this@from[chatId] = dbo.chatId
    this@from[chatType] = dbo.chatType
    this@from[characterId] = dbo.characterId
    this@from[characterName] = dbo.characterName
    this@from[characterAuthorId] = dbo.characterAuthorId
    this@from[characterCategory] = dbo.characterCategory
    this@from[isImage] = dbo.isImage
    this@from[messageText] = dbo.messageText
    this@from[imageUrl] = dbo.imageUrl
    this@from[variantIndex] = dbo.variantIndex
    this@from[variantsCount] = dbo.variantsCount
    this@from[model] = dbo.model
    this@from[generationInfo] = dbo.generationInfo
    this@from[nsfw] = dbo.nsfw
    this@from[language] = dbo.language
    this@from[messageCreatedAt] = dbo.messageCreatedAt
}

// ---- feedbacks ----

fun ResultRow.toFeedbackDbo(): FeedbackDbo = with(Tables.Feedbacks) {
    FeedbackDbo(
        id = this@toFeedbackDbo[id],
        createdAt = this@toFeedbackDbo[createdAt],
        userId = this@toFeedbackDbo[userId],
        text = this@toFeedbackDbo[text],
    )
}

fun UpdateBuilder<*>.from(dbo: FeedbackDbo) = with(Tables.Feedbacks) {
    this@from[id] = dbo.id
    this@from[createdAt] = dbo.createdAt
    this@from[userId] = dbo.userId
    this@from[text] = dbo.text
}

// ---- search_suggestions ----

fun ResultRow.toSearchSuggestionDbo(): SearchSuggestionDbo = with(Tables.SearchSuggestions) {
    SearchSuggestionDbo(
        term = this@toSearchSuggestionDbo[term],
        displayText = this@toSearchSuggestionDbo[displayText],
        searchCount = this@toSearchSuggestionDbo[searchCount],
        isAllowedToShow = this@toSearchSuggestionDbo[isAllowedToShow],
        isCharacterName = this@toSearchSuggestionDbo[isCharacterName],
        language = this@toSearchSuggestionDbo[language],
        createdAt = this@toSearchSuggestionDbo[createdAt],
        lastSearchedAt = this@toSearchSuggestionDbo[lastSearchedAt],
    )
}

fun UpdateBuilder<*>.from(dbo: SearchSuggestionDbo) = with(Tables.SearchSuggestions) {
    this@from[term] = dbo.term
    this@from[displayText] = dbo.displayText
    this@from[searchCount] = dbo.searchCount
    this@from[isAllowedToShow] = dbo.isAllowedToShow
    this@from[isCharacterName] = dbo.isCharacterName
    this@from[language] = dbo.language
    this@from[createdAt] = dbo.createdAt
    this@from[lastSearchedAt] = dbo.lastSearchedAt
}

// ---- user_notifications ----

fun ResultRow.toUserNotificationDbo(): UserNotificationDbo = with(Tables.UserNotifications) {
    UserNotificationDbo(
        id = this@toUserNotificationDbo[id],
        userId = this@toUserNotificationDbo[userId],
        type = this@toUserNotificationDbo[type],
        createdAt = this@toUserNotificationDbo[createdAt],
        updatedAt = this@toUserNotificationDbo[updatedAt],
        isRead = this@toUserNotificationDbo[isRead],
        actorUserId = this@toUserNotificationDbo[actorUserId],
        characterId = this@toUserNotificationDbo[characterId],
        commentId = this@toUserNotificationDbo[commentId],
        count = this@toUserNotificationDbo[count],
        milestone = this@toUserNotificationDbo[milestone],
        stackKey = this@toUserNotificationDbo[stackKey],
    )
}

fun UpdateBuilder<*>.from(dbo: UserNotificationDbo) = with(Tables.UserNotifications) {
    this@from[id] = dbo.id
    this@from[userId] = dbo.userId
    this@from[type] = dbo.type
    this@from[createdAt] = dbo.createdAt
    this@from[updatedAt] = dbo.updatedAt
    this@from[isRead] = dbo.isRead
    this@from[actorUserId] = dbo.actorUserId
    this@from[characterId] = dbo.characterId
    this@from[commentId] = dbo.commentId
    this@from[count] = dbo.count
    this@from[milestone] = dbo.milestone
    this@from[stackKey] = dbo.stackKey
}

// ---- character_activity_logs ----

fun ResultRow.toCharacterActivityLogDbo(): CharacterActivityLogDbo =
    with(Tables.CharacterActivityLogs) {
        CharacterActivityLogDbo(
            id = this@toCharacterActivityLogDbo[id],
            timestamp = this@toCharacterActivityLogDbo[timestamp],
            characterId = this@toCharacterActivityLogDbo[characterId],
            activityType = this@toCharacterActivityLogDbo[activityType],
            userId = this@toCharacterActivityLogDbo[userId],
        )
    }

fun UpdateBuilder<*>.from(dbo: CharacterActivityLogDbo) = with(Tables.CharacterActivityLogs) {
    this@from[id] = dbo.id
    this@from[timestamp] = dbo.timestamp
    this@from[characterId] = dbo.characterId
    this@from[activityType] = dbo.activityType
    this@from[userId] = dbo.userId
}

// ---- кэши рекомендаций ----

fun ResultRow.toUserRecommendationsCacheDbo(): UserRecommendationsCacheDbo =
    with(Tables.UserRecommendationsCache) {
        UserRecommendationsCacheDbo(
            userId = this@toUserRecommendationsCacheDbo[userId],
            characterIds = this@toUserRecommendationsCacheDbo[characterIds].toStringList(),
            updatedAt = this@toUserRecommendationsCacheDbo[updatedAt],
            version = this@toUserRecommendationsCacheDbo[version],
        )
    }

fun UpdateBuilder<*>.from(dbo: UserRecommendationsCacheDbo) = with(Tables.UserRecommendationsCache) {
    this@from[userId] = dbo.userId
    this@from[characterIds] = dbo.characterIds.toJson()
    this@from[updatedAt] = dbo.updatedAt
    this@from[version] = dbo.version
}

fun ResultRow.toCategoryRecommendationsCacheDbo(): CategoryRecommendationsCacheDbo =
    with(Tables.CategoryRecommendationsCache) {
        CategoryRecommendationsCacheDbo(
            categoryCode = this@toCategoryRecommendationsCacheDbo[categoryCode],
            characterIds = this@toCategoryRecommendationsCacheDbo[characterIds].toStringList(),
            updatedAt = this@toCategoryRecommendationsCacheDbo[updatedAt],
            version = this@toCategoryRecommendationsCacheDbo[version],
        )
    }

fun UpdateBuilder<*>.from(dbo: CategoryRecommendationsCacheDbo) =
    with(Tables.CategoryRecommendationsCache) {
        this@from[categoryCode] = dbo.categoryCode
        this@from[characterIds] = dbo.characterIds.toJson()
        this@from[updatedAt] = dbo.updatedAt
        this@from[version] = dbo.version
    }

fun ResultRow.toDefaultRecommendationsCacheDbo(): DefaultRecommendationsCacheDbo =
    with(Tables.DefaultRecommendationsCache) {
        DefaultRecommendationsCacheDbo(
            id = this@toDefaultRecommendationsCacheDbo[id],
            characterIds = this@toDefaultRecommendationsCacheDbo[characterIds].toStringList(),
            updatedAt = this@toDefaultRecommendationsCacheDbo[updatedAt],
            version = this@toDefaultRecommendationsCacheDbo[version],
        )
    }

fun UpdateBuilder<*>.from(dbo: DefaultRecommendationsCacheDbo) =
    with(Tables.DefaultRecommendationsCache) {
        this@from[id] = dbo.id
        this@from[characterIds] = dbo.characterIds.toJson()
        this@from[updatedAt] = dbo.updatedAt
        this@from[version] = dbo.version
    }

fun ResultRow.toDiscoverSectionsCacheDbo(): DiscoverSectionsCacheDbo =
    with(Tables.DiscoverSectionsCache) {
        DiscoverSectionsCacheDbo(
            id = this@toDiscoverSectionsCacheDbo[id],
            sections = Db.json.decodeFromString(
                sectionsSerializer, this@toDiscoverSectionsCacheDbo[sections],
            ),
            updatedAt = this@toDiscoverSectionsCacheDbo[updatedAt],
        )
    }

fun UpdateBuilder<*>.from(dbo: DiscoverSectionsCacheDbo) = with(Tables.DiscoverSectionsCache) {
    this@from[id] = dbo.id
    this@from[sections] = Db.json.encodeToString(sectionsSerializer, dbo.sections)
    this@from[updatedAt] = dbo.updatedAt
}

// ---- character_list_copy ----

fun ResultRow.toCharacterListCopyDbo(): CharacterListCopyDbo = with(Tables.CharacterListCopies) {
    CharacterListCopyDbo(
        id = this@toCharacterListCopyDbo[id],
        userId = this@toCharacterListCopyDbo[userId],
        deviceId = this@toCharacterListCopyDbo[deviceId],
        listType = this@toCharacterListCopyDbo[listType],
        characterIds = this@toCharacterListCopyDbo[characterIds].toStringList(),
        currentPosition = this@toCharacterListCopyDbo[currentPosition],
        baseListVersion = this@toCharacterListCopyDbo[baseListVersion],
        createdAt = this@toCharacterListCopyDbo[createdAt],
        lastAccessedAt = this@toCharacterListCopyDbo[lastAccessedAt],
        totalFound = this@toCharacterListCopyDbo[totalFound],
    )
}

fun UpdateBuilder<*>.from(dbo: CharacterListCopyDbo) = with(Tables.CharacterListCopies) {
    this@from[id] = dbo.id
    this@from[userId] = dbo.userId
    this@from[deviceId] = dbo.deviceId
    this@from[listType] = dbo.listType
    this@from[characterIds] = dbo.characterIds.toJson()
    this@from[currentPosition] = dbo.currentPosition
    this@from[baseListVersion] = dbo.baseListVersion
    this@from[createdAt] = dbo.createdAt
    this@from[lastAccessedAt] = dbo.lastAccessedAt
    this@from[totalFound] = dbo.totalFound
}

// ---- device_limit_carryover ----

fun ResultRow.toDeviceLimitCarryoverDbo(): DeviceLimitCarryoverDbo =
    with(Tables.DeviceLimitCarryovers) {
        DeviceLimitCarryoverDbo(
            deviceId = this@toDeviceLimitCarryoverDbo[deviceId],
            hourlyMessageCount = this@toDeviceLimitCarryoverDbo[hourlyMessageCount],
            dailyMessageCount = this@toDeviceLimitCarryoverDbo[dailyMessageCount],
            monthlyMessageCount = this@toDeviceLimitCarryoverDbo[monthlyMessageCount],
            monthlyTopModelCount = this@toDeviceLimitCarryoverDbo[monthlyTopModelCount],
            dailyImageCount = this@toDeviceLimitCarryoverDbo[dailyImageCount],
            monthlyTopImageCount = this@toDeviceLimitCarryoverDbo[monthlyTopImageCount],
            savedAt = this@toDeviceLimitCarryoverDbo[savedAt],
        )
    }

fun UpdateBuilder<*>.from(dbo: DeviceLimitCarryoverDbo) = with(Tables.DeviceLimitCarryovers) {
    this@from[deviceId] = dbo.deviceId
    this@from[hourlyMessageCount] = dbo.hourlyMessageCount
    this@from[dailyMessageCount] = dbo.dailyMessageCount
    this@from[monthlyMessageCount] = dbo.monthlyMessageCount
    this@from[monthlyTopModelCount] = dbo.monthlyTopModelCount
    this@from[dailyImageCount] = dbo.dailyImageCount
    this@from[monthlyTopImageCount] = dbo.monthlyTopImageCount
    this@from[savedAt] = dbo.savedAt
}

// ---- entity_id_stats ----

fun ResultRow.toDeletedIdsStatsDbo(): DeletedIdsStatsDbo = with(Tables.DeletedIdsStats) {
    DeletedIdsStatsDbo(
        entityType = this@toDeletedIdsStatsDbo[entityType],
        deletedIds = this@toDeletedIdsStatsDbo[deletedIds].toStringList().toSet(),
        createdAt = this@toDeletedIdsStatsDbo[createdAt],
        lastUpdated = this@toDeletedIdsStatsDbo[lastUpdated],
    )
}

fun UpdateBuilder<*>.from(dbo: DeletedIdsStatsDbo) = with(Tables.DeletedIdsStats) {
    this@from[entityType] = dbo.entityType
    this@from[deletedIds] = dbo.deletedIds.toList().toJson()
    this@from[createdAt] = dbo.createdAt
    this@from[lastUpdated] = dbo.lastUpdated
}
