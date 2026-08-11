package com.lvsmsmch.aichat.db

import org.jetbrains.exposed.sql.Table

/**
 * Схема Postgres: одна таблица на каждую бывшую коллекцию Mongo.
 *
 * Соответствие один-в-один с DBO — ключи остались теми же строками (hex
 * ObjectId), поэтому все перекрёстные ссылки между таблицами и всё, что уже
 * лежит на клиентах, продолжает совпадать.
 *
 * Поля-коллекции (теги, участники чата, переводы, co-occurrence) лежат в TEXT
 * как JSON — см. пояснение в [Db.json]. Внешних ключей нет намеренно: в Mongo
 * их не было, и данные местами ссылаются на уже удалённые сущности; включать
 * FK сейчас означало бы падать на переливке.
 *
 * Таймстемпы остались строками ISO-8601 UTC, как в Mongo. Это сознательно:
 * весь код сравнивает и сортирует их лексикографически, и перевод в timestamptz
 * потребовал бы переписать половину бизнес-логики. Лексикографический порядок
 * для ISO-8601 совпадает с хронологическим, так что сортировки и индексы верны.
 */
object Tables {

    object Users : Table("users") {
        val id = text("id")
        val createdAt = text("created_at")
        val lastActiveAt = text("last_active_at")
        val username = text("username")
        val name = text("name").nullable()
        val profilePictureUrl = text("profile_picture_url").nullable()
        val profilePictureUrlThumbnail = text("profile_picture_url_thumbnail").nullable()
        val email = text("email").nullable()
        val bio = text("bio").nullable()
        val googleOauthId = text("google_oauth_id").nullable()
        val accountType = text("account_type")
        val hasSubscription = bool("has_subscription")
        val characterLanguage = text("character_language")
        val deviceId = text("device_id").nullable()
        /** Apple sub из identity-токена: у Apple это единственный стабильный id. */
        val appleOauthId = text("apple_oauth_id").nullable()
        val hashedPassword = text("hashed_password").nullable()
        val privateCharacterCount = integer("private_character_count")
        val publicCharacterCount = integer("public_character_count")
        val followerCount = integer("follower_count")
        val followingCount = integer("following_count")
        val hourlyMessageCount = integer("hourly_message_count")
        val dailyMessageCount = integer("daily_message_count")
        val monthlyMessageCount = integer("monthly_message_count")
        val monthlyTopModelCount = integer("monthly_top_model_count")
        val dailyImageCount = integer("daily_image_count")
        val monthlyTopImageCount = integer("monthly_top_image_count")
        val totalMessagesCount = integer("total_messages_count")
        val totalChatsCount = integer("total_chats_count")
        val extraFreeMessagesCount = integer("extra_free_messages_count")
        val fcmToken = text("fcm_token").nullable()
        val limitPushStage = integer("limit_push_stage")
        val lastLimitPushAt = text("last_limit_push_at").nullable()
        val lastWinbackPushAt = text("last_winback_push_at").nullable()
        val color = text("color")
        val trialUsed = bool("trial_used")
        /**
         * Почта подтверждена кодом из письма. У аккаунтов Google — сразу true:
         * адрес подтверждён самим Google.
         */
        val emailVerified = bool("email_verified").default(false)

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, email)
            index(false, googleOauthId)
            index(false, appleOauthId)
            index(false, username)
            index(false, lastActiveAt)
            index(false, deviceId)
        }
    }

    /**
     * Коды из писем: подтверждение почты и сброс пароля.
     *
     * Сам код не хранится — только его bcrypt-хеш: утечка таблицы не даёт
     * войти в чужой аккаунт. Код короткий (6 цифр), поэтому живёт минуты,
     * одноразовый и с ограничением на число попыток.
     */
    object AuthCodes : Table("auth_codes") {
        val id = text("id")
        val userId = text("user_id")
        /** EMAIL_VERIFY либо PASSWORD_RESET. */
        val purpose = text("purpose")
        val codeHash = text("code_hash")
        /** Адрес, на который код отправлен: у смены почты он не равен текущему. */
        val email = text("email")
        val createdAt = text("created_at")
        val expiresAt = text("expires_at")
        /** Сколько раз вводили неверно — защита от подбора шести цифр. */
        val attempts = integer("attempts").default(0)
        /** Использован: повторно тем же кодом войти нельзя. */
        val consumedAt = text("consumed_at").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, userId, purpose)
            index(false, expiresAt)
        }
    }

    /**
     * Блокировка перебора пароля. Ключ — почта в нижнем регистре: лимит по IP
     * (rate-limit Ktor) сам по себе не спасает, атака идёт из многих адресов.
     */
    object AuthLockouts : Table("auth_lockouts") {
        val loginKey = text("login_key")
        val failedCount = integer("failed_count").default(0)
        val lockedUntil = text("locked_until").nullable()
        val lastFailedAt = text("last_failed_at")

        override val primaryKey = PrimaryKey(loginKey)
    }

    object Characters : Table("characters") {
        val id = text("id")
        val createdAt = text("created_at")
        val authorId = text("author_id")
        val name = text("name")
        val description = text("description")
        val prompt = text("prompt")
        val initialMessage = text("initial_message")
        val picUrl = text("pic_url").nullable()
        val picUrlThumbnail = text("pic_url_thumbnail").nullable()
        /**
         * Обложка чатов с персонажем: код встроенной («space», «neon_bar») или
         * URL своей картинки. Новым персонажам проставляется случайной.
         */
        val cover = text("cover").nullable()
        val visibility = integer("visibility")
        val category = text("category")
        /** JSON-массив кодов тегов. */
        val tags = text("tags")
        val totalChats = integer("total_chats")
        val totalMessages = integer("total_messages")
        val totalReviews = integer("total_reviews")
        val totalComments = integer("total_comments")
        val totalLikes = integer("total_likes")
        val averageRating = float("average_rating")
        val trendingScore = float("trending_score")
        val trendingScoreUpdatedAt = text("trending_score_updated_at").nullable()
        val recommendationScore = float("recommendation_score")
        val recommendationScoreUpdatedAt = text("recommendation_score_updated_at").nullable()
        val recommendationsScoreMultiplier = float("recommendations_score_multiplier").nullable()
        /** JSON-объект characterId → вес. */
        val coOccurrenceScore = text("co_occurrence_score")
        val coOccurrenceScoreUpdatedAt = text("co_occurrence_score_updated_at").nullable()
        /** JSON-массив id похожих персонажей. */
        val similarCharacterIds = text("similar_character_ids")
        val similarCharactersUpdatedAt = text("similar_characters_updated_at").nullable()
        val color = text("color")
        val topRank = integer("top_rank").nullable()
        /** JSON-объект язык → перевод. */
        val translations = text("translations")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, authorId)
            index(false, visibility)
            index(false, visibility, category)
            index(false, createdAt)
            index(false, averageRating)
            index(false, totalMessages)
            index(false, trendingScore)
            index(false, recommendationScore)
            index(false, name)
            index(false, authorId, visibility)
            index(false, visibility, recommendationScore)
        }
    }

    object Chats : Table("chats") {
        val id = text("id")
        val clientId = text("client_id")
        val lastModifiedAt = text("last_modified_at")
        val createdAt = text("created_at")
        val type = text("type")
        val userId = text("user_id")
        /** JSON-массив id участников (у DIRECT — один). */
        val characterIds = text("character_ids")
        val isMuted = bool("is_muted")
        val customName = text("custom_name").nullable()
        /** Обложка КОНКРЕТНОГО чата; null — берётся обложка персонажа. */
        val cover = text("cover").nullable()
        val isFirstChatWithThisCharacter = bool("is_first_chat_with_this_character")
        val isDeleted = bool("is_deleted")
        val deletedAt = text("deleted_at")
        val color = text("color")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, userId)
            index(false, clientId)
            index(false, userId, lastModifiedAt)
            index(false, userId, isDeleted)
            index(false, userId, type)
            index(false, userId, createdAt)
            index(false, userId, deletedAt)
        }
    }

    object Messages : Table("messages") {
        val id = text("id")
        val clientId = text("client_id")
        val lastModifiedAt = text("last_modified_at")
        val createdAt = text("created_at")
        val chatId = text("chat_id")
        val chatClientId = text("chat_client_id")
        val senderId = text("sender_id")
        val isSentByUser = bool("is_sent_by_user")
        val text = text("text")
        val imageUrl = text("image_url").nullable()
        val isImage = bool("is_image")
        val imageDebugInfo = text("image_debug_info").nullable()
        val isRead = bool("is_read")
        val status = text("status")
        val failReason = text("fail_reason").nullable()
        val nsfw = bool("nsfw")
        /**
         * Все сгенерированные варианты ответа персонажа, JSON-массив строк.
         * Первый элемент — исходный ответ, дальше добавляются ретраи.
         */
        val variants = text("variants").default("[]")
        /**
         * Индекс варианта, который выбрал юзер. Колонка [text] всегда держит
         * ЕГО текст — поэтому вся остальная логика (история для модели, синк,
         * превью в списке чатов) работает без изменений.
         */
        val selectedVariant = integer("selected_variant").default(0)
        val isDeleted = bool("is_deleted")
        val deletedAt = text("deleted_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, chatId, createdAt)
            index(false, clientId)
            index(false, senderId)
            index(false, chatId, isDeleted)
            index(false, chatId, lastModifiedAt)
            index(false, chatId, deletedAt)
            index(false, isRead, senderId)
            index(false, status)
        }
    }

    /**
     * Оценки ответов ИИ («палец вверх/вниз») — материал для статистики
     * качества генерации.
     *
     * Живут ОТДЕЛЬНО от сообщений и переживают удаление чата, сообщения и
     * даже персонажа: поэтому здесь не ссылки, а снимок всего, что нужно для
     * разбора, — текст ответа (или ссылка на картинку), модель, персонаж,
     * автор персонажа, подписка юзера на момент оценки.
     *
     * Одна строка на «сообщение + вариант + юзер»: у 1/2 может стоять лайк,
     * у 2/2 дизлайк. Снятая оценка не удаляет строку, а ставит [rating] = 0 —
     * «поставил и передумал» тоже сигнал.
     */
    object MessageRatings : Table("message_ratings") {
        val id = text("id")
        val createdAt = text("created_at")
        val updatedAt = text("updated_at")
        /** 1 — палец вверх, -1 — вниз, 0 — оценку сняли. */
        val rating = integer("rating")
        val userId = text("user_id")
        val userHasSubscription = bool("user_has_subscription").default(false)
        /** Серверный и клиентский id сообщения: строка переживёт его удаление. */
        val messageId = text("message_id")
        val messageClientId = text("message_client_id")
        val chatId = text("chat_id")
        val chatType = text("chat_type")
        val characterId = text("character_id")
        val characterName = text("character_name").nullable()
        val characterAuthorId = text("character_author_id").nullable()
        val characterCategory = text("character_category").nullable()
        val isImage = bool("is_image").default(false)
        /** Копия оценённого варианта ответа — сообщение может быть удалено. */
        val messageText = text("message_text").default("")
        val imageUrl = text("image_url").nullable()
        val variantIndex = integer("variant_index").default(0)
        val variantsCount = integer("variants_count").default(0)
        /** Модель генерации, вытащенная из дебаг-строки, и сама строка целиком. */
        val model = text("model").nullable()
        val generationInfo = text("generation_info").nullable()
        val nsfw = bool("nsfw").default(false)
        val language = text("language").nullable()
        val messageCreatedAt = text("message_created_at").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, userId)
            index(false, characterId)
            index(false, model)
            index(false, rating)
            index(false, createdAt)
            uniqueIndex(messageId, variantIndex, userId)
        }
    }

    object Sessions : Table("sessions") {
        val id = text("id")
        val token = text("token")
        val createdAt = text("created_at")
        val expiresAt = text("expires_at")
        val userId = text("user_id")
        val ipAddress = text("ip_address")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, token)
            index(false, userId)
            index(false, ipAddress)
        }
    }

    object Comments : Table("comments") {
        val id = text("id")
        val createdAt = text("created_at")
        val characterId = text("character_id")
        val authorId = text("author_id")
        val parentId = text("parent_id").nullable()
        val replyToUserId = text("reply_to_user_id").nullable()
        val text = text("text")
        val likesCount = integer("likes_count")
        val repliesCount = integer("replies_count")
        val editedAt = text("edited_at").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, characterId, parentId, createdAt)
            index(false, parentId, createdAt)
            index(false, authorId)
        }
    }

    object CommentLikes : Table("comment_likes") {
        val id = text("id")
        val userId = text("user_id")
        val commentId = text("comment_id")
        val likedAt = text("liked_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, userId)
            index(false, commentId)
        }
    }

    object Reviews : Table("reviews") {
        val id = text("id")
        val createdAt = text("created_at")
        val characterId = text("character_id")
        val isAnonymous = bool("is_anonymous")
        val authorId = text("author_id")
        val rating = integer("rating")
        val text = text("text").nullable()
        val likesCount = integer("likes_count")
        val editedAt = text("edited_at").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, characterId)
            index(false, characterId, createdAt)
            index(false, characterId, rating)
            index(false, characterId, likesCount)
            index(false, authorId, characterId)
            index(false, authorId)
        }
    }

    object ReviewLikes : Table("review_likes") {
        val id = text("id")
        val userId = text("user_id")
        val reviewId = text("review_id")
        val likedAt = text("liked_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, userId)
            index(false, reviewId)
            index(false, likedAt)
        }
    }

    object CharacterLikes : Table("character_likes") {
        val id = text("id")
        val userId = text("user_id")
        val characterId = text("character_id")
        val likedAt = text("liked_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, userId)
            index(false, characterId)
            index(true, userId, characterId)
        }
    }

    object Follows : Table("follows") {
        val id = text("id")
        val followedAt = text("followed_at")
        val followerId = text("follower_id")
        val followeeId = text("followee_id")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, followerId)
            index(false, followeeId)
            index(false, followerId, followeeId)
            index(false, followedAt)
        }
    }

    /**
     * Кто кого заблокировал. Читается на каждый список персонажей, поэтому
     * индекс по blocker_id обязателен, а пара — уникальная: блокировать
     * дважды нечего.
     */
    object UserBlocks : Table("user_blocks") {
        val id = text("id")
        val blockedAt = text("blocked_at")
        val blockerId = text("blocker_id")
        val blockedId = text("blocked_id")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, blockerId)
            index(false, blockedId)
            index(true, blockerId, blockedId)
        }
    }

    object Reports : Table("reports") {
        val id = text("id")
        val reportedAt = text("reported_at")
        val reportedBy = text("reported_by")
        val entityType = text("entity_type")
        val entityId = text("entity_id")
        val reason = text("reason")
        val text = text("text")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, entityId)
            index(false, entityType)
            index(false, reportedBy)
            index(false, reportedAt)
            index(false, entityType, entityId)
        }
    }

    object Feedbacks : Table("feedbacks") {
        val id = text("id")
        val createdAt = text("created_at")
        val userId = text("user_id")
        val text = text("text")

        override val primaryKey = PrimaryKey(id)
    }

    object SearchSuggestions : Table("search_suggestions") {
        /** Ключ — сам поисковый терм (как _id в Mongo). */
        val term = text("term")
        val displayText = text("display_text")
        val searchCount = long("search_count")
        val isAllowedToShow = bool("is_allowed_to_show")
        val isCharacterName = bool("is_character_name")
        val language = text("language").nullable()
        val createdAt = text("created_at")
        val lastSearchedAt = text("last_searched_at").nullable()

        override val primaryKey = PrimaryKey(term)

        init {
            index(false, searchCount)
            index(false, term, searchCount)
        }
    }

    object UserNotifications : Table("user_notifications") {
        val id = text("id")
        val userId = text("user_id")
        val type = text("type")
        val createdAt = text("created_at")
        val updatedAt = text("updated_at")
        val isRead = bool("is_read")
        val actorUserId = text("actor_user_id").nullable()
        val characterId = text("character_id").nullable()
        val commentId = text("comment_id").nullable()
        val count = integer("count")
        val milestone = integer("milestone")
        val stackKey = text("stack_key").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, userId, updatedAt)
            index(false, userId, isRead)
            index(false, stackKey)
        }
    }

    object CharacterActivityLogs : Table("character_activity_logs") {
        val id = text("id")
        val timestamp = text("timestamp")
        val characterId = text("character_id")
        val activityType = integer("activity_type")
        val userId = text("user_id")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, characterId)
            index(false, timestamp)
            index(false, characterId, activityType)
            index(false, userId)
        }
    }

    object UserRecommendationsCache : Table("user_recommendations_cache") {
        val userId = text("user_id")
        val characterIds = text("character_ids")
        val updatedAt = text("updated_at")
        val version = text("version")

        override val primaryKey = PrimaryKey(userId)
    }

    object CategoryRecommendationsCache : Table("category_cache") {
        val categoryCode = text("category_code")
        val characterIds = text("character_ids")
        val updatedAt = text("updated_at")
        val version = text("version")

        override val primaryKey = PrimaryKey(categoryCode)
    }

    object DefaultRecommendationsCache : Table("default_personalized_cache") {
        val id = text("id")
        val characterIds = text("character_ids")
        val updatedAt = text("updated_at")
        val version = text("version")

        override val primaryKey = PrimaryKey(id)
    }

    object DiscoverSectionsCache : Table("discover_sections") {
        /** userId либо "__default". */
        val id = text("id")
        /** JSON-массив секций (ключ + id персонажей). */
        val sections = text("sections")
        val updatedAt = text("updated_at")

        override val primaryKey = PrimaryKey(id)
    }

    object CharacterListCopies : Table("character_list_copy") {
        val id = text("id")
        val userId = text("user_id")
        val deviceId = text("device_id")
        val listType = text("list_type")
        val characterIds = text("character_ids")
        val currentPosition = integer("current_position")
        val baseListVersion = text("base_list_version")
        val createdAt = text("created_at")
        val lastAccessedAt = text("last_accessed_at")
        val totalFound = integer("total_found").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, userId)
            index(false, deviceId)
            index(false, userId, listType)
            index(false, lastAccessedAt)
        }
    }

    object DeviceLimitCarryovers : Table("device_limit_carryover") {
        val deviceId = text("device_id")
        val hourlyMessageCount = integer("hourly_message_count")
        val dailyMessageCount = integer("daily_message_count")
        val monthlyMessageCount = integer("monthly_message_count")
        val monthlyTopModelCount = integer("monthly_top_model_count")
        val dailyImageCount = integer("daily_image_count")
        val monthlyTopImageCount = integer("monthly_top_image_count")
        val savedAt = text("saved_at")

        override val primaryKey = PrimaryKey(deviceId)
    }

    /** Rolling-окна генерации аватаров: одна блокируемая строка на пользователя. */
    object AvatarGenerationLimits : Table("avatar_generation_limits") {
        val userId = text("user_id")
        val hourlyWindowStartedAt = long("hourly_window_started_at")
        val hourlyCount = integer("hourly_count")
        val dailyWindowStartedAt = long("daily_window_started_at")
        val dailyCount = integer("daily_count")
        /** Последняя генерация нужна для восстановления после обрыва клиента в фоне. */
        val generationRequestId = text("generation_request_id").nullable()
        val generationStatus = text("generation_status").nullable()
        val generatedImageUrl = text("generated_image_url").nullable()
        val generationStartedAt = long("generation_started_at").nullable()

        override val primaryKey = PrimaryKey(userId)
    }

    object DeletedIdsStats : Table("entity_id_stats") {
        val entityType = text("entity_type")
        /** JSON-массив удалённых id. */
        val deletedIds = text("deleted_ids")
        val createdAt = text("created_at")
        val lastUpdated = text("last_updated")

        override val primaryKey = PrimaryKey(entityType)
    }

    /** Все таблицы — для создания схемы и для сверки после переливки. */
    val all: List<Table> = listOf(
        Users, AuthCodes, AuthLockouts, Characters, Chats, Messages, Sessions,
        Comments, CommentLikes, Reviews, ReviewLikes, CharacterLikes,
        Follows, UserBlocks, Reports, Feedbacks, SearchSuggestions, UserNotifications,
        CharacterActivityLogs, UserRecommendationsCache,
        CategoryRecommendationsCache, DefaultRecommendationsCache,
        DiscoverSectionsCache, CharacterListCopies, DeviceLimitCarryovers, AvatarGenerationLimits,
        DeletedIdsStats, MessageRatings,
    )
}
