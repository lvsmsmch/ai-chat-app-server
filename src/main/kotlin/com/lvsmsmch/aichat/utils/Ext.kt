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

fun sendVerificationCode(email: String, verificationCode: Int) {
    val config = loadConfig()

    val properties = Properties().apply {
        put("mail.smtp.auth", "true")
        put("mail.smtp.starttls.enable", "true")
        put("mail.smtp.host", config.getProperty("SMTP_HOST"))
        put("mail.smtp.port", config.getProperty("SMTP_PORT"))
    }

    val session = Session.getInstance(properties, object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication {
            return PasswordAuthentication(
                config.getProperty("SMTP_USERNAME"),
                config.getProperty("SMTP_PASSWORD")
            )
        }
    })

    try {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.getProperty("SMTP_USERNAME"), "My app name"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(email))
            subject = "Your Verification Code"
            setText("Your verification code is: $verificationCode\n\nIf you didn't register, just ignore this email.")
        }

        Transport.send(message)
        println("Email sent successfully to $email")
    } catch (e: MessagingException) {
        e.printStackTrace()
        println("Failed to send email: ${e.message}")
    }
}


suspend fun uploadImageOnServer(image: File): String {
    return UUID.randomUUID().toString() + ".jpg"
}

fun generateUniqueUsername(): String {
    val adjectives = listOf("Fast", "Cool", "Brave", "Happy", "Sly", "Mighty", "Wild", "Fierce", "Clever")
    val nouns = listOf("Tiger", "Falcon", "Wizard", "Ninja", "Panther", "Wolf", "Phoenix", "Warrior", "Shadow")
    return "${adjectives.random()}${nouns.random()}${(1000..9999).random()}"
}
