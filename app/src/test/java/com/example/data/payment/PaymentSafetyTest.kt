package com.example.data.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class PaymentSafetyTest {
    @Test fun requestId_isDurableAcrossManagerRestart() {
        val store = InMemoryCheckoutRequestStore()
        val first = CheckoutRequestIdManager(store).getOrCreate("owner-a", "pkg-1")
        val second = CheckoutRequestIdManager(store).getOrCreate("owner-a", "pkg-1")
        assertEquals(first, second)
    }

    @Test fun requestId_isOwnerAndPackageBound() {
        val manager = CheckoutRequestIdManager(InMemoryCheckoutRequestStore())
        val first = manager.getOrCreate("owner-a", "pkg-1")
        assertNotEquals(first, manager.getOrCreate("owner-b", "pkg-1"))
        assertNotEquals(first, manager.getOrCreate("owner-a", "pkg-2"))
    }

    @Test fun requestId_concurrentCallersShareOneValue() {
        val store = InMemoryCheckoutRequestStore()
        val manager = CheckoutRequestIdManager(store)
        val values = mutableSetOf<String?>()
        val lock = Any()
        val done = CountDownLatch(12)
        val pool = Executors.newFixedThreadPool(12)
        repeat(12) { pool.execute { synchronized(lock) { values += manager.getOrCreate("owner", "pkg") }; done.countDown() } }
        done.await(); pool.shutdown()
        assertEquals(1, values.size)
    }

    @Test fun terminalClear_requiresAuthoritativeTerminalAndExactId() {
        val store = InMemoryCheckoutRequestStore(); val manager = CheckoutRequestIdManager(store)
        val id = manager.getOrCreate("owner", "pkg")!!
        assertFalse(manager.clearAfterAuthoritativeTerminal("owner", "pkg", id, PaymentStatus.AMBIGUOUS))
        assertFalse(manager.clearAfterAuthoritativeTerminal("owner", "pkg", "wrong", PaymentStatus.COMPLETED))
        assertTrue(manager.clearAfterAuthoritativeTerminal("owner", "pkg", id, PaymentStatus.COMPLETED))
        assertNull(manager.getOrCreate("owner", "pkg").takeIf { it == id })
    }

    @Test fun unavailableSecureStore_failsClosed() {
        assertNull(CheckoutRequestIdManager(UnavailableCheckoutRequestStore).getOrCreate("owner", "pkg"))
    }

    @Test fun captureAndHistory_neverPromoteRefundedReversedOrAmbiguous() {
        assertEquals(PaymentStatus.REFUNDED, PaymentStatusVerifier.captureStatus(true, "{\"success\":true,\"status\":\"refunded\"}"))
        assertEquals(PaymentStatus.REVERSED, PaymentStatusVerifier.captureStatus(true, "{\"success\":true,\"status\":\"reversed\"}"))
        assertEquals(PaymentStatus.AMBIGUOUS, PaymentStatusVerifier.captureStatus(true, "{\"success\":true}"))
        assertEquals("refunded", PaymentStatusVerifier.historyStatus("refunded"))
        assertEquals("reversed", PaymentStatusVerifier.historyStatus("reversed"))
        assertEquals("ambiguous", PaymentStatusVerifier.historyStatus("network-failure"))
    }

    @Test fun releasePolicy_isHardDisabledByDefault() {
        assertFalse(DisabledPaymentReleasePolicy.allows(PaymentProvider.PAYPAL_STANDARD, PaymentOperation.INITIATE))
        assertFalse(DisabledPaymentReleasePolicy.allows(PaymentProvider.PAYPAL_STANDARD, PaymentOperation.CAPTURE))
        assertFalse(DisabledPaymentReleasePolicy.allows(PaymentProvider.PAYHERO, PaymentOperation.INITIATE))
    }
}
