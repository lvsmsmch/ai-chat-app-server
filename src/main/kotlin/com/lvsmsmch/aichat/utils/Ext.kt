package com.lvsmsmch.aichat.utils

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mindrot.jbcrypt.BCrypt
import java.io.File
import java.security.SecureRandom
import java.util.*
import kotlin.random.Random

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

suspend fun uploadImageOnServer(image: File): String {
    return UUID.randomUUID().toString() + ".jpg"
}

fun generateUniqueUsername(): String {
    val adjectives = listOf("Fast", "Cool", "Brave", "Happy", "Sly", "Mighty", "Wild", "Fierce", "Clever")
    val nouns = listOf("Tiger", "Falcon", "Wizard", "Ninja", "Panther", "Wolf", "Phoenix", "Warrior", "Shadow")
    return "${adjectives.random()}${nouns.random()}${(1000..9999).random()}"
}
