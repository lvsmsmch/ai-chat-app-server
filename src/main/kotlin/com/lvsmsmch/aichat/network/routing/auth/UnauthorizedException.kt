/**
 * Public routing functions for auth token handling
 */
package com.lvsmsmch.aichat.network.routing.auth

class UnauthorizedException(override val message: String) : Exception(message)