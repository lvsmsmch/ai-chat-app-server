package com.lvsmsmch.aichat._trash.billing.network

import com.lvsmsmch.aichat.auth.database.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat._trash.billing.database.BillingService
import com.lvsmsmch.aichat._trash.billing.database.SubscriptionRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.CouldNotVerifyPurchase
import com.lvsmsmch.aichat.utils.ForbiddenException
import com.lvsmsmch.aichat.utils.UserNotFoundException
import com.lvsmsmch.aichat.utils.respondSuccess
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Routing.billingRoutes(
    sessionRepository: SessionRepository,
    userRepository: UserRepository,
    subscriptionRepository: SubscriptionRepository,
    billingService: BillingService
) {

    route("/billing") {
        post("/verify-purchase") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val userId = sessionDbo.userId
            val userDbo = userRepository.getUserById(userId) ?: throw UserNotFoundException()
            if (userDbo.googleOauthId.isNullOrBlank()) {
                throw ForbiddenException("Premium requires Google account")
            }
            val request = call.receive<VerifyPurchaseRequest>()
            val status = billingService.verifyPurchase(userId, request) ?: throw CouldNotVerifyPurchase()
            val backgroundImageId = request.backgroundImageId   // todo analytics
            call.respondSuccess(data = status)
        }

        get("/subscription-status") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val userId = sessionDbo.userId
            val status = billingService.getSubscriptionStatus(userId)
            call.respondSuccess(data = status)
        }

        post("/refresh-subscription") {
            val sessionDbo = sessionRepository.verifyToken(call)
            val userId = sessionDbo.userId
            val status = billingService.refreshSubscriptionStatus(userId)
            call.respondSuccess(data = status)
        }
    }
}