package com.lvsmsmch.aichat.mail

import com.lvsmsmch.aichat.auth.Emails
import com.lvsmsmch.aichat.utils.logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * Отправка писем: подтверждение адреса и сброс пароля.
 *
 * За интерфейсом, потому что реализация зависит от инфраструктуры: пока нет
 * домена и ключа провайдера, письма просто пишутся в лог — флоу при этом можно
 * тестировать целиком, код виден в логе. Появится домен — включается [ResendMailSender]
 * одной переменной окружения, остальной код не меняется.
 */
interface MailSender {
    /** @return true, если письмо принято к отправке. */
    suspend fun send(to: String, subject: String, text: String): Boolean
}

/**
 * Заглушка на время, пока нет домена: письмо не уходит никуда, а его текст
 * (вместе с кодом) виден в журнале сервера. Для боевого режима не годится —
 * поэтому и логируется предупреждением.
 */
class LoggingMailSender : MailSender {
    override suspend fun send(to: String, subject: String, text: String): Boolean {
        logger.warn(
            "MAIL NOT SENT (провайдер не настроен). to=${Emails.mask(to)} " +
                "subject=$subject\n$text"
        )
        return true
    }
}

/**
 * Resend: HTTP API, ключ и домен-отправитель из окружения. Выбран за то, что
 * не требует SMTP-обвязки и не кладёт письма в спам при настроенном домене.
 */
class ResendMailSender(
    private val apiKey: String,
    private val from: String,
    private val client: HttpClient,
) : MailSender {

    @Serializable
    private data class ResendRequest(
        val from: String,
        val to: List<String>,
        val subject: String,
        val text: String,
    )

    override suspend fun send(to: String, subject: String, text: String): Boolean {
        return runCatching {
            val response = client.post("https://api.resend.com/emails") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(ResendRequest(from = from, to = listOf(to), subject = subject, text = text))
            }
            if (!response.status.isSuccess()) {
                // Тело ответа — единственный способ понять, что не так с доменом или ключом
                logger.error(
                    "Resend отказал: ${response.status} ${response.bodyAsText().take(300)}"
                )
                false
            } else {
                logger.info("Письмо отправлено на ${Emails.mask(to)}: $subject")
                true
            }
        }.getOrElse { e ->
            logger.error("Не удалось отправить письмо на ${Emails.mask(to)}: ${e.message}", e)
            false
        }
    }
}

object MailSenderFactory {

    /**
     * Ключ есть — отправляем по-настоящему; нет — пишем в лог. Так один и тот
     * же код работает и на дроплете без домена, и в бою.
     */
    fun create(client: HttpClient): MailSender {
        val apiKey = System.getenv("RESEND_API_KEY")?.takeIf { it.isNotBlank() }
        val from = System.getenv("MAIL_FROM")?.takeIf { it.isNotBlank() }
        return if (apiKey != null && from != null) {
            logger.info("Почта: Resend, отправитель $from")
            ResendMailSender(apiKey = apiKey, from = from, client = client)
        } else {
            logger.warn("Почта: провайдер не настроен (нет RESEND_API_KEY/MAIL_FROM), письма идут в лог")
            LoggingMailSender()
        }
    }
}

/**
 * Тексты писем. Намеренно без HTML: короткое письмо с кодом лучше проходит
 * спам-фильтры, и его не нужно вычитывать в двух версиях.
 */
object MailTemplates {

    private val appName: String get() = System.getenv("APP_NAME") ?: "AI Chat"

    fun verifyEmail(code: String, ttlMinutes: Long): Pair<String, String> =
        "$appName: confirm your email" to """
            Your confirmation code: $code

            Enter it in the app to finish signing up.
            The code is valid for $ttlMinutes minutes.

            If you didn't request this, just ignore this email.
        """.trimIndent()

    fun resetPassword(code: String, ttlMinutes: Long): Pair<String, String> =
        "$appName: password reset" to """
            Your password reset code: $code

            Enter it in the app to set a new password.
            The code is valid for $ttlMinutes minutes.

            If you didn't request a reset, ignore this email — your password stays unchanged.
        """.trimIndent()
}
