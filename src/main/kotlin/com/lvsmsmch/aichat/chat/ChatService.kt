package com.lvsmsmch.aichat.chat

import com.lvsmsmch.aichat.character.database.ActivityType
import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.database.ChatDbo
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageDbo
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.notification.NotificationService
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.TransactionHelper

/**
 * Чаты и сообщения: запись вместе со всеми счётчиками, которые от неё зависят.
 *
 * Счётчики персонажа и юзера, журнал активности и само сообщение обязаны
 * меняться одной транзакцией — на них держатся лимиты, тренды и статистика
 * профиля. Уведомления и вехи шлются ПОСЛЕ коммита, иначе откат оставил бы
 * юзера с пушем о том, чего не произошло.
 */
class ChatService(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val characterRepository: CharacterRepository,
    private val userRepository: UserRepository,
    private val characterActivityLogRepository: CharacterActivityLogRepository,
    private val notificationService: NotificationService,
    private val transactionHelper: TransactionHelper,
) {

    suspend fun addChat(chatDbo: ChatDbo) {
        transactionHelper.withTransaction { session ->
            chatRepository.insertChat(session, chatDbo)
            userRepository.notifyChatWasCreated(session, chatDbo.userId)
            chatDbo.characterIds.forEach { characterId ->

                if (chatDbo.isFirstChatWithThisCharacter) {
                    characterRepository.incrementChatsCount(session, characterId, 1)
                }

                characterActivityLogRepository.logActivity(
                    session = session,
                    activityType = ActivityType.CHAT_CREATED,
                    characterId = characterId,
                    userId = chatDbo.userId
                )
            }
        }
    }

    suspend fun addMessage(messageDbo: MessageDbo) {
        transactionHelper.withTransaction { session ->
            messageRepository.insertMessage(session, messageDbo)
            // Изображения НЕ тратят лимит сообщений — у них свой дневной счётчик
            if (!messageDbo.isSentByUser && !messageDbo.isImage) {
                val chat = chatRepository.getChatById(session, messageDbo.chatId)!!
                userRepository.notifyCharacterMessageWasSent(session, chat.userId)
                characterRepository.incrementMessagesCount(session, messageDbo.senderId, 1)
                characterActivityLogRepository.logActivity(
                    session = session,
                    activityType = ActivityType.MESSAGE_SENT,
                    characterId = messageDbo.senderId,
                    userId = chat.userId
                )
            }
        }
        // Вне транзакции: веха 1к/10к/100к сообщений персонажа
        if (!messageDbo.isSentByUser && !messageDbo.isImage) {
            notificationService.checkCharacterMilestone(messageDbo.senderId)
        }
    }
}
