package com.cyclecare.app

import com.cyclecare.app.data.supabase.AuthErrorMessages
import com.cyclecare.app.data.supabase.AuthOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthErrorMessagesTest {
    @Test
    fun accountCreationLoginAndGuestFailuresUseDifferentCopy() {
        val signup = AuthErrorMessages.forResponse("Invalid API key", AuthOperation.SIGN_UP)
        val signin = AuthErrorMessages.forResponse("Invalid API key", AuthOperation.SIGN_IN)
        val guest = AuthErrorMessages.forResponse("Invalid API key", AuthOperation.GUEST)

        assertEquals("We couldn't create your account right now. Please try again later.", signup)
        assertEquals("We couldn't sign you in right now. Please try again later.", signin)
        assertEquals("Guest access is temporarily unavailable. Please try again later.", guest)
        listOf(signup, signin, guest).forEach {
            assertFalse(it.contains("API", ignoreCase = true))
            assertFalse(it.contains("Supabase", ignoreCase = true))
        }
    }

    @Test
    fun commonAuthProblemsHaveSafeActionableCopy() {
        assertEquals(
            "An account with that email already exists. Sign in or reset your password.",
            AuthErrorMessages.forResponse("User already registered", AuthOperation.SIGN_UP)
        )
        assertEquals(
            "That email is already linked to another account. Sign out, then sign in to that account.",
            AuthErrorMessages.forResponse("User already registered", AuthOperation.ACCOUNT_LINK)
        )
        assertEquals(
            "Sign-in was cancelled. You can try again whenever you're ready.",
            AuthErrorMessages.forResponse("access_denied", AuthOperation.CALLBACK)
        )
    }
}
