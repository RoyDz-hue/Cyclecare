package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.payment.SupabasePaymentService
import com.cyclecare.app.security.AuthPreferences
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Fallback-parity pins for the transaction_history_status_v1 twin (NEW
 * IMPLEMENTATION, 2026-11-13): the twin is the verbatim pre-wiring
 * checkTransactionStatus response interpretation, run here against the REAL
 * AOSP org.json classes at sdk 34. Reflection reaches the private twin; the
 * production function stays untouched. Written, NOT executed in this
 * workspace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransactionHistoryStatusFallbackParityTest {

    private fun service(): SupabasePaymentService {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AuthPreferences(
            context,
            context.getSharedPreferences("ths-twin-${UUID.randomUUID()}", Context.MODE_PRIVATE),
            true,
        )
        return SupabasePaymentService(prefs)
    }

    private fun twin(httpSuccessful: Boolean, body: String): String {
        val m = SupabasePaymentService::class.java.getDeclaredMethod(
            "kotlinTransactionHistoryStatus", Boolean::class.java, String::class.java,
        )
        m.isAccessible = true
        return m.invoke(service(), httpSuccessful, body) as String
    }

    /** The twin's throw paths land in the caller's catch -> "ambiguous". */
    private fun twinViaCatch(httpSuccessful: Boolean, body: String): String =
        try {
            twin(httpSuccessful, body)
        } catch (e: Exception) {
            "ambiguous"
        }

    @Test
    fun gateAndThrowPathsAreAmbiguous() {
        assertEquals("ambiguous", twinViaCatch(false, """[{"status":"success"}]"""))
        assertEquals("ambiguous", twinViaCatch(true, ""))
        // Kotlin isBlank: an NBSP-only body is blank.
        assertEquals("ambiguous", twinViaCatch(true, " \u00A0 "))
        // JSONArray(body) throws on non-array roots.
        assertEquals("ambiguous", twinViaCatch(true, "not json"))
        assertEquals("ambiguous", twinViaCatch(true, """{"status":"success"}"""))
        // Empty array; getJSONObject(0) throws on non-object rows.
        assertEquals("ambiguous", twinViaCatch(true, "[]"))
        assertEquals("ambiguous", twinViaCatch(true, "[null]"))
        assertEquals("ambiguous", twinViaCatch(true, """["x"]"""))
    }

    @Test
    fun mappingsMatchHistoryStatus() {
        assertEquals("success", twin(true, """[{"status":"SUCCESS"}]"""))
        assertEquals("success", twin(true, """[{"status":"Paid"}]"""))
        assertEquals("pending", twin(true, """[{"status":" processing "}]"""))
        assertEquals("cancelled", twin(true, """[{"status":"CANCELLED"}]"""))
        assertEquals("refunded", twin(true, """[{"status":"refunded"}]"""))
        assertEquals("ambiguous", twin(true, """[{"status":"weird"}]"""))
    }

    @Test
    fun optStringSemanticsPreserved() {
        // Absent status -> the "pending" default.
        assertEquals("pending", twin(true, "[{}]"))
        // JSONObject.NULL -> String.valueOf(NULL) = the literal "null" ->
        // no historyStatus branch -> "ambiguous".
        assertEquals("ambiguous", twin(true, """[{"status":null}]"""))
        // Numeric status coerces via String.valueOf (200.0 -> "200.0").
        assertEquals("ambiguous", twin(true, """[{"status":200}]"""))
        assertEquals("ambiguous", twin(true, """[{"status":200.0}]"""))
        // Only the FIRST row is read; duplicate keys are last-wins.
        assertEquals("success", twin(true, """[{"status":"success"},{"status":"failed"}]"""))
        assertEquals("failed", twin(true, """[{"status":"pending","status":"failed"}]"""))
    }

    @Test
    fun sanityJsonArrayReferenceBehavior() {
        // Reference: the real org.json JSONArray on this runtime parses the
        // lenient payload the twin relies on.
        val array = JSONArray("""[{"status":"success"}]""")
        assertEquals("success", array.getJSONObject(0).optString("status", "pending"))
    }
}
