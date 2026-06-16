package com.lvsmsmch.aichat.utils

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import java.util.*

class CorrelationIdPlugin {
    companion object Plugin : BaseApplicationPlugin<Application, CorrelationIdPluginConfig, CorrelationIdPlugin> {
        override val key = AttributeKey<CorrelationIdPlugin>("CorrelationId")
        
        val CORRELATION_ID_KEY = AttributeKey<String>("CorrelationId")
        
        override fun install(pipeline: Application, configure: CorrelationIdPluginConfig.() -> Unit): CorrelationIdPlugin {
            val config = CorrelationIdPluginConfig().apply(configure)
            
            pipeline.intercept(ApplicationCallPipeline.Monitoring) {
                val correlationId = call.request.header(config.headerName) ?: UUID.randomUUID().toString()
                call.attributes.put(CORRELATION_ID_KEY, correlationId)
                call.response.header(config.headerName, correlationId)
            }
            
            return CorrelationIdPlugin()
        }
    }
}

class CorrelationIdPluginConfig {
    var headerName: String = "X-Correlation-ID"
}

val ApplicationCall.correlationId: String
    get() = this.attributes[CorrelationIdPlugin.CORRELATION_ID_KEY]