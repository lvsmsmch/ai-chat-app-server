package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.network.routing.chat.*
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
        polymorphic(MessagesWsRequest::class) {
            subclass(MessagesWsRequest.SendMessage::class)
            subclass(MessagesWsRequest.ReloadMessage::class)
            subclass(MessagesWsRequest.EditMessage::class)
            subclass(MessagesWsRequest.MarkAsRead::class)
            subclass(MessagesWsRequest.MarkAsReadAll::class)
            subclass(MessagesWsRequest.DeleteMessage::class)
            subclass(MessagesWsRequest.DeleteMessageRange::class)
            subclass(MessagesWsRequest.DeleteAllMessages::class)
        }
        polymorphic(MessagesWsEvent::class) {
            subclass(MessagesWsEvent.NewMessage::class)
            subclass(MessagesWsEvent.MessageEdited::class)
            subclass(MessagesWsEvent.CharacterTyping::class)
            subclass(MessagesWsEvent.MessageRangeDeleted::class)
            subclass(MessagesWsEvent.AllMessagesDeleted::class)
            subclass(MessagesWsEvent.ChatDeleted::class)
            subclass(MessagesWsEvent.Error::class)
        }
        polymorphic(ChatsWsRequest::class) {
        }
        polymorphic(ChatsWsEvent::class) {
            subclass(ChatsWsEvent.ChatAdded::class)
            subclass(ChatsWsEvent.ChatChanged::class)
            subclass(ChatsWsEvent.ChatRangeDeleted::class)
            subclass(ChatsWsEvent.Error::class)
        }
        polymorphic(PingPongMessage::class) {
            subclass(PingPongMessage.Ping::class)
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

fun generateUniqueUsername(): String {
    val adjectives = listOf("Fast", "Cool", "Brave", "Happy", "Sly", "Mighty", "Wild", "Fierce", "Clever")
    val nouns = listOf("Tiger", "Falcon", "Wizard", "Ninja", "Panther", "Wolf", "Phoenix", "Warrior", "Shadow")
    return "${adjectives.random()}${nouns.random()}${(1000..9999).random()}"
}
