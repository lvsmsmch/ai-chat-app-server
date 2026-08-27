package com.lvsmsmch.aichat

import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.SLF4JLogger
import com.lvsmsmch.aichat.db.Db
import com.lvsmsmch.aichat.di.appModule
import com.lvsmsmch.aichat.jobs.BackgroundJobs
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.utils.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.*
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

fun main() {
    logger.info("Application started...")
    embeddedServer(Netty, port = 8080) {
        logger.info("Server started...")
        module()
        logger.info("Module configured...")
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    // gzip: JSON жмётся в 4-6 раз, крупные списки перестают весить сотни КБ
    install(Compression) { gzip() }
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(60)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    install(RateLimit) {
        global {
            rateLimiter(limit = 1000, refillPeriod = 1.minutes)
        }

        register(RateLimitName("ip-based")) {
            // 100/мин не хватало: активная навигация по приложению (профили,
            // лента, лимиты, синки) легко превышала порог и валила экраны в 429
            rateLimiter(limit = 400, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.origin.remoteHost
            }
        }

        register(RateLimitName("auth-strict")) {
            rateLimiter(limit = 5, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.origin.remoteHost
            }
        }

        register(RateLimitName("rewarded")) {
            rateLimiter(limit = 3, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["Authorization"] ?: call.request.origin.remoteHost
            }
        }

        register(RateLimitName("ai-search")) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["Authorization"] ?: call.request.origin.remoteHost
            }
        }
    }

    install(Koin) {
        logger(SLF4JLogger())
        modules(appModule)
    }

    install(CorrelationIdPlugin)
    configureErrorHandling()

    // Postgres: пул, схема (таблицы + индексы создаются, если их ещё нет)
    Db.connect()
    Db.createSchema(Tables.all)
    logger.info("Postgres connected, schema ensured: ${Tables.all.size} tables")

    // Всё остальное собирает Koin: репозитории и сервисы — в di/AppModule.kt,
    // зависимости роутинга — внутри configureRouting()
    val backgroundJobs by inject<BackgroundJobs>()
    backgroundJobs.start()

    // Обложки чатов: персонажам без обложки проставляем дефолтную. Разовая
    // операция, повторные старты ничего не меняют — она идемпотентна
    val charactersRepo by inject<com.lvsmsmch.aichat.character.database.CharacterRepository>()
    launch {
        // Набор обложек вырос — раскидываем персонажей по всему списку,
        // потом доставляем дефолтные тем, у кого обложки нет вовсе
        runCatching { charactersRepo.redistributeCovers() }
            .onSuccess { if (it > 0) logger.info("Chat covers redistributed: $it characters") }
            .onFailure { logger.warn("Cover redistribute failed: ${it.message}") }
        runCatching { charactersRepo.backfillCovers() }
            .onSuccess { if (it > 0) logger.info("Chat covers assigned: $it characters") }
            .onFailure { logger.warn("Cover backfill failed: ${it.message}") }
    }

    configureRouting()

    environment.monitor.subscribe(ApplicationStopping) {
        runBlocking {
            logger.info("Application stopping, cancelling background jobs...")
            backgroundJobs.stop()
        }
    }
}
