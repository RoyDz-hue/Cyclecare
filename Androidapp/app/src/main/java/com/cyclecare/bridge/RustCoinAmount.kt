package com.cyclecare.bridge

import com.cyclecare.app.rust.CycleCareCoreBridge
import com.cyclecare.app.ui.screens.sanitizeCoinAmountInput
import org.json.JSONObject

object RustCoinAmountConfig { const val ENABLED = false }

/** Coin transfer amount sanitizing through the Rust core, with the exact
 * production Kotlin path as fallback. Pure snapshot of one input string:
 * does not read or write accounts, logs or settings, and rejected input is
 * kept as typed in both implementations. */
object RustCoinAmount {
    fun sanitize(raw: String): Pair<String, String?> =
        nativeOrNull(raw) ?: sanitizeCoinAmountInput(raw)

    fun nativeOrNull(raw: String): Pair<String, String?>? {
        val ok = CycleCareCoreBridge.call("sanitize_coin_amount",
            JSONObject().put("raw", raw))?.optJSONObject("ok") ?: return null
        return guard(raw, decode(ok) ?: return null)
    }

    /** Defense-parity shadow, not new policy: the native answer is used only
     * when the decoded pair is EXACTLY what the production Kotlin oracle
     * returns for the same input; any difference and Kotlin wins. This rejects
     * rewritten amounts, unknown messages AND invalid accepted pairs. */
    internal fun guard(raw: String, decoded: Pair<String, String?>): Pair<String, String?>? =
        if (decoded == sanitizeCoinAmountInput(raw)) decoded else null

    internal fun decode(ok: JSONObject): Pair<String, String?>? = try {
        val text = ok.get("text")
        require(text is String)
        val rejection: String? = if (ok.isNull("rejection")) null else {
            val r = ok.get("rejection")
            require(r is String)
            r
        }
        text to rejection
    } catch (_: org.json.JSONException) { null } catch (_: IllegalArgumentException) { null }
}
