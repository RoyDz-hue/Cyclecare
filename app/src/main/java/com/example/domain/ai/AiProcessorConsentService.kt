package com.example.domain.ai

import com.example.data.supabase.SupabaseConfig
import com.example.security.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/** Server manifest and receipt are the only authority for cloud AI processing. */
data class AiProcessor(val id: String, val displayName: String, val model: String, val policyRevision: String,
    val manifestFingerprint: String, val processors: List<AiSubProcessor>, val eligible: Boolean,
    val unavailableReason: String, val consentEnabled: Boolean, val consentRevision: Int)
data class AiSubProcessor(val id: String, val name: String, val policyUrl: String)
data class AiProcessorManifest(val version: Int, val accountSnapshot: String, val manifestRevision: String,
    val providers: List<AiProcessor>, val hybridReleased: Boolean, val hybridMaxAttempts: Int)
data class AiProcessorReceipt(val providerId: String, val revision: Int, val manifestFingerprint: String)
sealed class ConsentResult {
    data class Success(val manifest: AiProcessorManifest, val receipt: AiProcessorReceipt? = null): ConsentResult()
    data class Failure(val reason: String): ConsentResult()
}

class AiProcessorConsentService(
    private val authPreferences: AuthPreferences,
    private val baseUrl: String = SupabaseConfig.url,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
) {
    companion object {
        const val API_PATH = "/functions/v1/ai-provider-consent"
        const val VERSION = 3
        private const val MAX_BODY = 64 * 1024
        private val IDS = setOf("COHERE", "GEMINI", "OPENROUTER")
        private val FINGERPRINT = Regex("^[a-f0-9]{64}$")
    }
    private fun request(token: String) = Request.Builder().url(baseUrl.trimEnd('/') + API_PATH)
        .header("apikey", SupabaseConfig.anonKey).header("Authorization", "Bearer ${token.trim()}")

    suspend fun fetchManifest(accessToken: String): ConsentResult = withContext(Dispatchers.IO) {
        if (accessToken.isBlank() || !authPreferences.isSecureStorageAvailable)
            return@withContext ConsentResult.Failure("Consent requires an authenticated secure session")
        val owner = authPreferences.effectiveUserId
        val session = authPreferences.sessionGeneration
        try {
            client.newCall(request(accessToken).get().build()).execute().use { response ->
                val raw = boundedBody(response) ?: return@withContext ConsentResult.Failure("Consent manifest unavailable")
                if (!response.isSuccessful) return@withContext ConsentResult.Failure("Consent manifest unavailable")
                val manifest = parseManifest(JSONObject(raw), owner)
                if (authPreferences.effectiveUserId != owner || authPreferences.sessionGeneration != session)
                    return@withContext ConsentResult.Failure("Consent session changed")
                ConsentResult.Success(manifest)
            }
        } catch (_: Exception) { ConsentResult.Failure("Consent manifest unavailable") }
    }

    suspend fun setConsent(accessToken: String, manifest: AiProcessorManifest, providerId: String, enabled: Boolean): ConsentResult = withContext(Dispatchers.IO) {
        if (accessToken.isBlank() || !authPreferences.isSecureStorageAvailable)
            return@withContext ConsentResult.Failure("Consent requires an authenticated secure session")
        val provider = manifest.providers.firstOrNull { it.id == providerId.trim().uppercase() }
            ?: return@withContext ConsentResult.Failure("Unknown provider")
        if (provider.id !in IDS || manifest.accountSnapshot != authPreferences.effectiveUserId || manifest.version != VERSION)
            return@withContext ConsentResult.Failure("Consent session changed")
        if (enabled && !provider.eligible) return@withContext ConsentResult.Failure(provider.unavailableReason.ifBlank { "Provider is unavailable" })
        if (provider.consentRevision < 0 || provider.consentRevision == Int.MAX_VALUE)
            return@withContext ConsentResult.Failure("Consent revision unavailable")
        val snapshot = authPreferences.beginProcessorConsentMutation(provider.id, enabled)
            ?: return@withContext ConsentResult.Failure("Resolve pending revocation before granting consent")
        val body = JSONObject().put("provider_id", provider.id).put("manifest_fingerprint", provider.manifestFingerprint)
            .put("enabled", enabled).put("expected_revision", provider.consentRevision)
        try {
            client.newCall(request(accessToken).header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
                val raw = boundedBody(response) ?: return@withContext ConsentResult.Failure("Consent update unavailable")
                if (!response.isSuccessful) return@withContext ConsentResult.Failure("Consent update rejected")
                val json = JSONObject(raw)
                val receipt = json.getJSONObject("receipt")
                if (number(json, "version") != VERSION || text(json, "account_snapshot") != snapshot.owner ||
                    text(json, "provider_id") != provider.id || text(json, "manifest_revision") != manifest.manifestRevision ||
                    boolean(receipt, "enabled") != enabled || number(receipt, "revision") != provider.consentRevision + 1)
                    return@withContext ConsentResult.Failure("Invalid consent receipt")
                val fingerprint = text(receipt, "manifest_fingerprint")
                if (enabled && fingerprint != provider.manifestFingerprint)
                    return@withContext ConsentResult.Failure("Invalid consent receipt")
                val saved = if (enabled) AiProcessorReceipt(provider.id, number(receipt, "revision"), fingerprint) else null
                if (!authPreferences.completeProcessorConsentMutation(snapshot, provider.id, saved))
                    return@withContext ConsentResult.Failure("Consent session or decision changed")
                val updated = manifest.copy(providers = manifest.providers.map {
                    if (it.id == provider.id) it.copy(consentEnabled = enabled, consentRevision = number(receipt, "revision")) else it
                })
                ConsentResult.Success(updated, saved)
            }
        } catch (_: Exception) { ConsentResult.Failure("Consent update unavailable") }
    }

    suspend fun flushPendingRevokes(accessToken: String): List<ConsentResult> {
        val results = mutableListOf<ConsentResult>()
        // Refresh before each CAS; no grant is inferred from a retained local preference.
        for (id in authPreferences.pendingProcessorRevokes().toList()) {
            when (val fetched = fetchManifest(accessToken)) {
                is ConsentResult.Success -> results += setConsent(accessToken, fetched.manifest, id, false)
                is ConsentResult.Failure -> { results += fetched; break }
            }
        }
        return results
    }

    fun cloudReceiptIsCurrent(manifest: AiProcessorManifest, providerId: String): Boolean {
        if (manifest.version != VERSION || manifest.accountSnapshot != authPreferences.effectiveUserId) return false
        val p = manifest.providers.firstOrNull { it.id == providerId } ?: return false
        val r = authPreferences.processorReceipt(providerId) ?: return false
        return p.eligible && p.consentEnabled && r.revision == p.consentRevision && r.manifestFingerprint == p.manifestFingerprint &&
            providerId !in authPreferences.pendingProcessorRevokes()
    }

    private fun boundedBody(response: okhttp3.Response): String? {
        val body = response.body ?: return null
        if (body.contentLength() > MAX_BODY) return null
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BODY + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_BODY) return null
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
    private fun text(o: JSONObject, key: String): String = (o.get(key) as? String) ?: error("Invalid text")
    private fun boolean(o: JSONObject, key: String): Boolean = (o.get(key) as? Boolean) ?: error("Invalid boolean")
    private fun number(o: JSONObject, key: String): Int {
        val value = o.get(key)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble())
        return value.toInt()
    }
    private fun parseManifest(root: JSONObject, owner: String): AiProcessorManifest {
        require(number(root, "version") == VERSION && text(root, "account_snapshot") == owner)
        val revision = text(root, "manifest_revision").also { require(it.isNotBlank() && it.length <= 128) }
        val array = root.getJSONArray("providers")
        require(array.length() in 1..IDS.size)
        val seen = mutableSetOf<String>()
        val providers = (0 until array.length()).map { index ->
            val p = array.getJSONObject(index)
            val id = text(p, "id").also { require(it in IDS && seen.add(it)) }
            val fp = text(p, "manifest_fingerprint").also { require(FINGERPRINT.matches(it)) }
            val processors = p.getJSONArray("processors").let { list ->
                require(list.length() in 1..8)
                (0 until list.length()).map { j ->
                    val sub = list.getJSONObject(j)
                    val name = text(sub, "name"); val subId = text(sub, "id"); val url = text(sub, "policy_url")
                    val uri = URI(url)
                    require(name.isNotBlank() && subId.isNotBlank() && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
                    AiSubProcessor(subId, name, url)
                }
            }
            val consent = p.getJSONObject("consent")
            val consentRevision = number(consent, "revision").also { require(it >= 0) }
            AiProcessor(id, text(p, "display_name").also { require(it.isNotBlank()) }, text(p, "model").also { require(it.isNotBlank()) },
                text(p, "policy_revision").also { require(it.isNotBlank()) }, fp, processors, boolean(p, "eligible"),
                text(p, "unavailable_reason"), boolean(consent, "enabled"), consentRevision)
        }
        val hybrid = root.getJSONObject("hybrid")
        return AiProcessorManifest(VERSION, owner, revision, providers, boolean(hybrid, "released"), number(hybrid, "max_attempts").also { require(it in 0..2) })
    }
}
