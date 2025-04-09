package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.network.routing.chat.*
import com.lvsmsmch.aichat.network.routing.notifications.NotificationWsEvent
import com.lvsmsmch.aichat.network.routing.notifications.NotificationWsRequest
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.mindrot.jbcrypt.BCrypt
import java.io.File
import java.security.SecureRandom
import java.util.*
import kotlin.random.Random


val defaultJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    serializersModule = SerializersModule {
        polymorphic(ChatWsRequest::class) {
            subclass(ChatWsRequest.SendMessage::class)
            subclass(ChatWsRequest.EditMessage::class)
            subclass(ChatWsRequest.DeleteMessage::class)
            subclass(ChatWsRequest.MarkAsRead::class)
            subclass(ChatWsRequest.MarkAsReadAll::class)
        }
        polymorphic(ChatWsEvent::class) {
            subclass(ChatWsEvent.CharacterTyping::class)
            subclass(ChatWsEvent.NewMessage::class)
            subclass(ChatWsEvent.MessageDeleted::class)
            subclass(ChatWsEvent.MessageEdited::class)
            subclass(ChatWsEvent.Error::class)
        }
        polymorphic(NotificationWsRequest::class) {
            subclass(NotificationWsRequest.Ping::class)
        }
        polymorphic(NotificationWsEvent::class) {
            subclass(NotificationWsEvent.Pong::class)
            subclass(NotificationWsEvent.ChatStateChanged::class)
            subclass(NotificationWsEvent.Error::class)
        }
    }
}

fun ApplicationCall.getUserIp(): String {
    val forwardedForHeader = request.header("X-Forwarded-For")
    if (!forwardedForHeader.isNullOrBlank()) {
        return forwardedForHeader.split(",")[0].trim()
    }

    return request.origin.remoteHost
}

fun loadConfig(): Properties {
    val properties = Properties()
    val inputStream = Thread.currentThread().contextClassLoader.getResourceAsStream("config.properties")
        ?: throw IllegalArgumentException("config.properties not found")

    properties.load(inputStream)
    return properties
}

fun generateToken(): String {
    val secureRandom = SecureRandom()
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

fun generateVerificationCode(): Int {
    return Random.nextInt(100_000, 999_999)
}

fun hashPassword(providedPassword: String): String {
    return BCrypt.hashpw(providedPassword, BCrypt.gensalt())
}

fun checkPassword(providedPassword: String, storedHash: String): Boolean {
    return BCrypt.checkpw(providedPassword, storedHash)
}

fun uploadImageOnServer(image: File): String {
    return UUID.randomUUID().toString() + ".jpg"
}

fun generateUniqueUsername(): String {
    val adjectives = listOf("Fast", "Cool", "Brave", "Happy", "Sly", "Mighty", "Wild", "Fierce", "Clever")
    val nouns = listOf("Tiger", "Falcon", "Wizard", "Ninja", "Panther", "Wolf", "Phoenix", "Warrior", "Shadow")
    return "${adjectives.random()}${nouns.random()}${(1000..9999).random()}"
}
