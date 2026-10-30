package com.lvsmsmch.aichat.utils

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import java.io.FileInputStream

/**
 * Отправка FCM-пушей. Ключ сервисного аккаунта Firebase берётся из env
 * FIREBASE_SERVICE_ACCOUNT (путь к json). Ключа нет — пуши тихо выключены,
 * весь остальной сервер работает как обычно.
 */
object FcmSender {

    private val enabled: Boolean by lazy {
        val path = System.getenv("FIREBASE_SERVICE_ACCOUNT")
        if (path.isNullOrBlank()) {
            logger.info("FIREBASE_SERVICE_ACCOUNT not set - push notifications disabled")
            return@lazy false
        }
        runCatching {
            FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(FileInputStream(path)))
                    .build()
            )
        }.onFailure {
            logger.error("Failed to init Firebase Admin: ${it.message}", it)
        }.isSuccess
    }

    /**
     * Что делать с токеном, который FCM больше не знает (приложение удалено
     * или токен сменился). Ставится на старте сервера: без этого каждый
     * проход винбэка и лимитов снова и снова бил в сотни мёртвых токенов.
     */
    @Volatile
    var onDeadToken: ((String) -> Unit)? = null

    /**
     * true — сообщение принято FCM (не гарантия доставки на девайс).
     * @param route deep-link для клиента: тап по пушу открывает нужный экран
     */
    fun send(token: String, title: String, body: String, route: String? = null): Boolean {
        if (!enabled) return false
        return runCatching {
            val builder = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
            route?.let { builder.putData("route", it) }
            FirebaseMessaging.getInstance().send(builder.build())
        }.onFailure {
            if (it is FirebaseMessagingException && it.messagingErrorCode == MessagingErrorCode.UNREGISTERED) {
                logger.info("FCM token is no longer registered - dropping it")
                onDeadToken?.invoke(token)
            } else {
                logger.error("FCM send failed: ${it.message}")
            }
        }.isSuccess
    }
}
