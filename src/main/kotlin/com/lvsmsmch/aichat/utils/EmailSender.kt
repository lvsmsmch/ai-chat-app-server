package com.lvsmsmch.aichat.utils

import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.*

class EmailSender {

    fun sendLoginVerificationCode(
        email: String,
        code: Int
    ) {
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
                setText("Your verification code is: $code\n\nIf you didn't try to login, just ignore this email.")
            }

            Transport.send(message)
            println("Email sent successfully to $email")
        } catch (e: MessagingException) {
            e.printStackTrace()
            println("Failed to send email: ${e.message}")
        }
    }

    fun sendRegistrationVerificationCode(
        email: String,
        code: Int
    ) {

    }

    fun sendLoginVerificationToken(
        email: String,
        token: String
    ) {

    }

    fun sendRegistrationVerificationToken(
        email: String,
        token: String
    ) {

    }

    fun sendPasswordResetVerificationToken(
        email: String,
        token: String
    ) {

    }
}