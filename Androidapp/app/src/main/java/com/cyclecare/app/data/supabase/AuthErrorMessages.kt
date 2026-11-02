package com.cyclecare.app.data.supabase

import org.json.JSONObject

internal enum class AuthOperation {
    SIGN_UP,
    SIGN_IN,
    MAGIC_LINK,
    PASSWORD_RESET,
    GUEST,
    CALLBACK,
    ACCOUNT_LINK,
    GOOGLE,
    SET_PASSWORD
}

internal object AuthErrorMessages {
    fun forResponse(body: String, operation: AuthOperation): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val details = listOf("code", "error_code", "msg", "message", "error_description", "error")
            .mapNotNull { key -> parsed?.optString(key)?.takeIf(String::isNotBlank) }
            .joinToString(" ")
            .ifBlank { body }
            .lowercase()

        if (operation == AuthOperation.SIGN_IN &&
            listOf("invalid login credentials", "invalid_credentials", "wrong password", "incorrect password")
                .any(details::contains)
        ) {
            return "That email and password don't match. Check them and try again."
        }
        if (operation == AuthOperation.SIGN_UP &&
            listOf("already registered", "already exists", "user_exists", "email_exists")
                .any(details::contains)
        ) {
            return "An account with that email already exists. Sign in or reset your password."
        }
        if (operation == AuthOperation.ACCOUNT_LINK &&
            listOf("already registered", "already exists", "user_exists", "email_exists")
                .any(details::contains)
        ) {
            return "That email is already linked to another account. Sign out, then sign in to that account."
        }
        if (operation == AuthOperation.SIGN_UP &&
            listOf("password should be at least", "password is too short", "weak_password")
                .any(details::contains)
        ) {
            return "Choose a password with at least 8 characters."
        }
        if (operation == AuthOperation.GUEST &&
            listOf("anonymous sign-ins are disabled", "anonymous sign in is disabled", "anonymous users are disabled")
                .any(details::contains)
        ) {
            return "Guest access is temporarily unavailable. Try again later or sign in with email."
        }
        if (operation == AuthOperation.ACCOUNT_LINK &&
            listOf("manual_linking_disabled", "manual linking is disabled").any(details::contains)
        ) {
            return "Linking with Google is not available yet. Use Create Account with your email to keep this device's progress."
        }
        if (operation == AuthOperation.CALLBACK && details.contains("access_denied")) {
            return "Sign-in was cancelled. You can try again whenever you're ready."
        }
        if (operation == AuthOperation.CALLBACK &&
            listOf("expired", "invalid token", "invalid code", "already been used", "otp_expired")
                .any(details::contains)
        ) {
            return "This CycleCare link has expired or was already used. Request a new link and try again."
        }
        if ((operation == AuthOperation.SIGN_IN || operation == AuthOperation.CALLBACK) &&
            listOf("email not confirmed", "email_not_confirmed", "email is not confirmed")
                .any(details::contains)
        ) {
            return "Please verify your email using the CycleCare link before signing in."
        }
        return fallback(operation)
    }

    fun fallback(operation: AuthOperation): String = when (operation) {
        AuthOperation.SIGN_UP -> "We couldn't create your account right now. Please try again later."
        AuthOperation.SIGN_IN -> "We couldn't sign you in right now. Please try again later."
        AuthOperation.MAGIC_LINK -> "We couldn't send a sign-in link right now. Please try again later."
        AuthOperation.PASSWORD_RESET -> "We couldn't send password reset instructions right now. Please try again later."
        AuthOperation.GUEST -> "Guest access is temporarily unavailable. Please try again later."
        AuthOperation.CALLBACK -> "We couldn't complete sign-in from that link. Request a new link and try again."
        AuthOperation.ACCOUNT_LINK -> "We couldn't link your account right now. Please try again later."
        AuthOperation.GOOGLE -> "Google sign-in couldn't be completed. Please try again."
        AuthOperation.SET_PASSWORD -> "Your password couldn't be updated right now. Please try again."
    }
}
