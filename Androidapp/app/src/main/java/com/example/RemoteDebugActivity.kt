package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import com.example.data.supabase.UserFacingServerMessage
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.example.ui.theme.CycleCareTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Visible only from the debug build's Settings screen. No screen capture, control or health-data access. */
class RemoteDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CycleCareTheme {
                RemoteDebugScreen(onBack = { finish() })
            }
        }
    }
}

private data class BridgeResponse(val body: JSONObject)

@Composable
private fun RemoteDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var sessionToken by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Not connected") }
    var showPrivacy by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TextButton(onClick = onBack) { Text("← Settings") }
            Text("Remote debug", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "A temporary, owner-approved diagnostics link to the development environment.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("What this sends", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Only when you tap Send diagnostic snapshot: app version, Android release/API level, internet connectivity and timestamp. It does not read or send cycle logs, symptoms, notes, account details, screen contents, contacts or identifiers.",
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = { showPrivacy = true }) { Text("Review privacy boundary") }

            if (sessionToken == null) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase(Locale.US).filter { c -> c.isLetterOrDigit() }.take(12) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("One-time pairing code") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it })
                    Text("I approve this temporary diagnostic session.", fontSize = 14.sp)
                }
                Button(
                    onClick = {
                        busy = true
                        status = "Pairing…"
                        scope.launch {
                            try {
                                val response = withContext(Dispatchers.IO) {
                                    postJson("/api/pair", JSONObject().put("code", code.trim()))
                                }
                                sessionToken = response.body.getString("token")
                                status = "Paired. No diagnostics have been sent yet."
                            } catch (e: Exception) {
                                status = "Couldn't pair this phone. Check the code and connection, then try again."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && consent && code.length >= 6,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Connecting…" else "Pair this phone") }
            } else {
                Button(
                    onClick = {
                        busy = true
                        status = "Sending the approved snapshot…"
                        scope.launch {
                            try {
                                val payload = buildDiagnosticPayload(context)
                                withContext(Dispatchers.IO) {
                                    postJson("/api/report", payload, sessionToken)
                                }
                                status = "Snapshot received by the debug environment."
                            } catch (e: Exception) {
                                status = "The diagnostic snapshot couldn't be sent. Check the connection and try again."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Sending…" else "Send diagnostic snapshot") }
                OutlinedButton(
                    onClick = {
                        val token = sessionToken
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { postJson("/api/disconnect", JSONObject(), token) }
                                status = "Session ended."
                            } catch (_: Exception) {
                                status = "Session ended on this phone."
                            } finally {
                                sessionToken = null
                                code = ""
                                consent = false
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("End session") }
            }

            Text("Status: $status", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("Bridge: ${BuildConfig.DEBUG_BRIDGE_URL}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Diagnostics privacy") },
            text = {
                Text("The pairing request sends only the one-time code. A snapshot is sent only after you press its button. The bridge accepts a fixed allowlist of technical fields. No remote screen viewing, touch control, background collection, or health-record access is included.")
            },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("Understood") } }
        )
    }
}

private fun buildDiagnosticPayload(context: Context): JSONObject {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivity.activeNetwork
    val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
    val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
    return JSONObject()
        .put("app_version", BuildConfig.VERSION_NAME.take(40))
        .put("android_release", Build.VERSION.RELEASE.take(20))
        .put("android_api", Build.VERSION.SDK_INT)
        .put("network_connected", connected)
        .put("reported_at_utc", timestamp)
}

private fun postJson(path: String, payload: JSONObject, bearerToken: String? = null): BridgeResponse {
    val base = BuildConfig.DEBUG_BRIDGE_URL.trimEnd('/')
    val url = URL("$base$path")
    require(url.protocol == "https") { "The debug bridge must use HTTPS." }
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 10_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json")
        bearerToken?.let { setRequestProperty("Authorization", "Bearer $it") }
    }
    try {
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val response = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        if (code !in 200..299) {
            throw IOException(
                UserFacingServerMessage.sanitize(
                    response.optString("error"),
                    "The request couldn't be completed. Check the connection and try again."
                )
            )
        }
        return BridgeResponse(response)
    } finally {
        connection.disconnect()
    }
}
