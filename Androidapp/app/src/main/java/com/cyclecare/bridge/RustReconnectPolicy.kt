package com.cyclecare.bridge

import com.cyclecare.app.domain.sync.ReconnectWorkPolicy
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject

object RustReconnectPolicyConfig { const val ENABLED = false }

/** Reconnect work policy decisions through the Rust core, with the exact
 * production Kotlin policy as fallback. The Kotlin policy MUTATES its State
 * (wasConnected on every decide, owner/lastRunAtMs reset on owner change,
 * lastRunAtMs on Run), so this adapter:
 *  - snapshots the pre-state BEFORE anything mutates it and builds the
 *    native input only from that snapshot,
 *  - runs the production Kotlin oracle on an ISOLATED copy (never twice on
 *    the shared state),
 *  - commits exactly ONE chosen post-state to the shared State. */
object RustReconnectPolicy {
    fun decide(
        state: ReconnectWorkPolicy.State,
        isConnected: Boolean,
        isEligible: Boolean,
        currentOwner: String?,
        nowMs: Long
    ): ReconnectWorkPolicy.Decision = step("decide_reconnect", state, isConnected, isEligible, currentOwner, nowMs)

    fun decideTrailing(
        state: ReconnectWorkPolicy.State,
        isConnected: Boolean,
        isEligible: Boolean,
        currentOwner: String?,
        nowMs: Long
    ): ReconnectWorkPolicy.Decision = step("decide_reconnect_trailing", state, isConnected, isEligible, currentOwner, nowMs)

    internal data class Pre(val wasConnected: Boolean, val lastRunAtMs: Long, val owner: String?)
    internal data class NativeStep(val decision: ReconnectWorkPolicy.Decision, val post: Pre)

    private fun step(
        contract: String,
        state: ReconnectWorkPolicy.State,
        isConnected: Boolean,
        isEligible: Boolean,
        currentOwner: String?,
        nowMs: Long
    ): ReconnectWorkPolicy.Decision {
        val pre = Pre(state.wasConnected, state.lastRunAtMs, state.owner)
        val oracleState = ReconnectWorkPolicy.State(pre.wasConnected, pre.lastRunAtMs, pre.owner)
        val kotlinDecision = if (contract == "decide_reconnect_trailing")
            ReconnectWorkPolicy.decideTrailing(oracleState, isConnected, isEligible, currentOwner, nowMs)
        else
            ReconnectWorkPolicy.decide(oracleState, isConnected, isEligible, currentOwner, nowMs)
        val kotlinPost = Pre(oracleState.wasConnected, oracleState.lastRunAtMs, oracleState.owner)
        val native = nativeStep(contract, pre, isConnected, isEligible, currentOwner, nowMs)
        // Defense-parity shadow, not new policy: the native decision is used only when the
        // decoded decision AND returned state EXACTLY equal the production Kotlin oracle for the
        // same pre-state and inputs; any difference and Kotlin wins. The shared state is always
        // committed exactly once, to the Kotlin post-state (identical to native when matched).
        state.wasConnected = kotlinPost.wasConnected
        state.lastRunAtMs = kotlinPost.lastRunAtMs
        state.owner = kotlinPost.owner
        return if (native != null && native.decision == kotlinDecision && native.post == kotlinPost)
            native.decision else kotlinDecision
    }

    internal fun nativeStep(
        contract: String,
        pre: Pre,
        isConnected: Boolean,
        isEligible: Boolean,
        currentOwner: String?,
        nowMs: Long
    ): NativeStep? {
        val params = JSONObject()
            .put("state", JSONObject()
                .put("wasConnected", pre.wasConnected)
                .put("lastRunAtMs", pre.lastRunAtMs)
                .put("owner", if (pre.owner == null) JSONObject.NULL else pre.owner))
            .put("isConnected", isConnected)
            .put("isEligible", isEligible)
            .put("currentOwner", if (currentOwner == null) JSONObject.NULL else currentOwner)
            .put("nowMs", nowMs)
        val reply = CycleCareCoreBridge.call(contract, params) ?: return null
        return decode(reply)
    }

    /** Reply shape: {"ok": {"kind": ...}, "state": {...}} - state is a SIBLING of ok. */
    internal fun decode(reply: JSONObject): NativeStep? = try {
        val ok = reply.getJSONObject("ok")
        val decision = when (ok.getString("kind")) {
            "Run" -> ReconnectWorkPolicy.Decision.Run
            "Skip" -> ReconnectWorkPolicy.Decision.Skip
            "Throttled" -> ReconnectWorkPolicy.Decision.Throttled(ok.getLong("retryAfterMs"))
            else -> return null
        }
        val ns = reply.getJSONObject("state")
        val owner: String? = if (ns.isNull("owner")) null else {
            val o = ns.get("owner")
            require(o is String)
            o
        }
        NativeStep(decision, Pre(ns.getBoolean("wasConnected"), ns.getLong("lastRunAtMs"), owner))
    } catch (_: org.json.JSONException) { null } catch (_: IllegalArgumentException) { null }
}
