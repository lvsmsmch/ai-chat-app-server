package com.lvsmsmch.aichat.app_data.network

import com.lvsmsmch.aichat.utils.respondSuccess
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Route.configureAppDataRouting() {
    route("/app-data") {
        
        get("/paywall-images") {
            val imageUrls = listOf(
                "http://localhost:8080/images/1.jpg",
                "http://localhost:8080/images/2.jpg",
                "http://localhost:8080/images/3.jpg",
            )
            call.respondSuccess(data = PaywallImagesResponse(imageUrls))
        }

    }
}