package com.cyclecare.app.domain.payment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.economy.EconomyResult
import com.cyclecare.app.data.economy.SupabaseEconomyService
import com.cyclecare.app.data.payment.SupabasePaymentService
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Pure polling callbacks and explicit JVM preferences; no backend or payment request is sent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaymentUseCaseTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private fun prefs(): AuthPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AuthPreferences(context, context.getSharedPreferences("poll-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
            .apply { userId = owner; isLocalOnlyMode = false }
    }
    private fun useCase(
        prefs: AuthPreferences,
        status: suspend (String) -> String = { "success" },
        wallet: suspend (String) -> EconomyResult<EconomyProfile>
    ) = PaymentUseCase(SupabasePaymentService(prefs), SupabaseEconomyService(prefs), prefs, status, wallet)

    @Test fun confirmedPaymentWithFailedWalletNeverReturnsStaleSuccess() = runBlocking {
        val p = prefs()
        val result = useCase(p) { EconomyResult.Error("offline") }
            .pollPaymentStatus("reference", 1, EconomyProfile(userId = owner, coinBalance = 19))
        assertTrue(result is PaymentPollResult.Timeout)
        assertTrue((result as PaymentPollResult.Timeout).message.contains("do not pay again"))
        assertFalse(result.message.contains("will update automatically"))
    }

    @Test fun onlyFreshAuthoritativeWalletIsDisplayed() = runBlocking {
        val p = prefs()
        val verified = EconomyProfile(userId = owner, coinBalance = 250)
        val result = useCase(p) { requested -> assertEquals(owner, requested); EconomyResult.Success(verified) }
            .pollPaymentStatus("reference", 1, EconomyProfile(userId = owner, coinBalance = 19))
        assertEquals(verified, (result as PaymentPollResult.Success).profile)
    }

    @Test fun anotherOwnersWalletCannotBecomeSuccess() = runBlocking {
        val p = prefs()
        val result = useCase(p) { EconomyResult.Success(EconomyProfile(userId = "other", coinBalance = 5000)) }
            .pollPaymentStatus("reference", 1, EconomyProfile(userId = owner))
        assertTrue(result is PaymentPollResult.Timeout)
    }

    @Test fun staleStartingProfileDoesNotPollOrLoadWallet() = runBlocking {
        val p = prefs()
        var calls = 0
        val result = useCase(p, { calls++; "success" }) { calls++; EconomyResult.Error("unused") }
            .pollPaymentStatus("reference", 1, EconomyProfile(userId = "other"))
        assertEquals("auth_required", (result as PaymentPollResult.Failed).status)
        assertEquals(0, calls)
    }

    @Test fun accountABAAfterStatusCannotLoadWallet() = runBlocking {
        val p = prefs()
        var walletCalls = 0
        val result = useCase(p, { p.userId = "other"; p.userId = owner; "success" }) {
            walletCalls++; EconomyResult.Success(EconomyProfile(userId = owner))
        }.pollPaymentStatus("reference", 1, EconomyProfile(userId = owner))
        assertEquals("auth_required", (result as PaymentPollResult.Failed).status)
        assertEquals(0, walletCalls)
    }

    @Test fun logoutDuringWalletRefreshCannotDisplayOldWallet() = runBlocking {
        val p = prefs()
        val result = useCase(p) { p.clearSession(); EconomyResult.Success(EconomyProfile(userId = owner, coinBalance = 250)) }
            .pollPaymentStatus("reference", 1, EconomyProfile(userId = owner))
        assertEquals("auth_required", (result as PaymentPollResult.Failed).status)
    }

    @Test fun reversalsAndRefundsRemainTerminalRatherThanTimingOut() = runBlocking {
        for (status in listOf("refunded", "reversed")) {
            val p = prefs()
            var walletCalls = 0
            val result = useCase(p, { status }) { walletCalls++; EconomyResult.Error("unused") }
                .pollPaymentStatus("reference", 1, EconomyProfile(userId = owner))
            assertEquals(status, (result as PaymentPollResult.Failed).status)
            assertTrue(result.message.contains("reversed"))
            assertEquals(0, walletCalls)
        }
    }

    @Test fun unconfirmedOutcomeNeverPromisesAutomaticCreditOrInvitesRepayment() = runBlocking {
        val p = prefs()
        var calls = 0
        val result = useCase(p, { calls++; "pending" }) { calls++; EconomyResult.Error("unused") }
            .pollPaymentStatus("reference", 0, EconomyProfile(userId = owner))
        assertEquals(0, calls)
        assertTrue(result is PaymentPollResult.Timeout)
        assertTrue((result as PaymentPollResult.Timeout).message.contains("do not assume it failed"))
    }
}
