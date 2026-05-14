package ai.nomad.travel.ui

import ai.nomad.shared.relay.RelayMessage
import ai.nomad.travel.TravelApp
import ai.nomad.travel.relay.HeartbeatService
import ai.nomad.travel.relay.TravelRelay
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    app: TravelApp,
    padding: PaddingValues,
    onUnpair: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var pingMsg by remember { mutableStateOf<String?>(null) }

    var url by remember { mutableStateOf(app.prefs.relayBaseUrl) }
    var key by remember { mutableStateOf(app.prefs.accountKey) }
    var relaySaveMsg by remember { mutableStateOf<String?>(null) }
    var heartbeatOn by remember { mutableStateOf(app.prefs.heartbeatServiceEnabled) }
    val pm = remember { ctx.getSystemService(PowerManager::class.java) }
    val unrestricted = remember(heartbeatOn) {
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }

    Column(
        Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pairing", style = MaterialTheme.typography.titleMedium)
                Text("✅ Paired with home phone", color = MaterialTheme.colorScheme.primary)
                Text(
                    "Pairing ID: ${app.prefs.pairingId.take(8)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = {
                    // Snapshot the "last pong" timestamp before we send, then poll
                    // prefs for a fresh pong up to 10s. If it advances, it's a
                    // true round-trip confirmation, not just a successful send.
                    val before = app.prefs.lastPongAt
                    val sentAt = System.currentTimeMillis()
                    pingMsg = "Pinging…"
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            TravelRelay.send(app, RelayMessage(type = RelayMessage.Type.PING))
                        }
                        if (res.isFailure) {
                            pingMsg = "❌ Send failed: ${res.exceptionOrNull()?.message ?: "unknown"}"
                            return@launch
                        }
                        // Poll for up to 10s for a fresher pong.
                        var elapsed = 0L
                        while (elapsed < 10_000L) {
                            val after = app.prefs.lastPongAt
                            if (after > before && after >= sentAt) {
                                pingMsg = "✅ Home replied in ${after - sentAt} ms"
                                return@launch
                            }
                            delay(200)
                            elapsed += 200
                        }
                        pingMsg = "❌ No reply in 10s — home phone may be offline " +
                            "or not receiving pushes."
                    }
                }) { Text("Ping home phone") }
                pingMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = {
                    app.prefs.clearPairing()
                    onUnpair()
                }) { Text("Unpair") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Background connection", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keeps a persistent notification so Android doesn't kill the " +
                        "background heartbeat. Without this, your phone may stop " +
                        "checking in with home after a few hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Persistent heartbeat (recommended)")
                    Switch(
                        checked = heartbeatOn,
                        onCheckedChange = {
                            heartbeatOn = it
                            app.prefs.heartbeatServiceEnabled = it
                            if (it) HeartbeatService.startIfEnabled(ctx)
                            else HeartbeatService.stop(ctx)
                        }
                    )
                }
                val sentAt = app.prefs.lastHeartbeatSentAt
                val pongAt = app.prefs.lastPongAt
                Text(
                    when {
                        !heartbeatOn -> "Heartbeat off — Android may kill background updates."
                        pongAt > 0 -> "Last home reply: ${ageLabel(pongAt)}"
                        sentAt > 0 -> "Last ping sent: ${ageLabel(sentAt)} (no reply yet)"
                        else -> "Heartbeat starting…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (heartbeatOn && !unrestricted) {
                    Text(
                        "⚠️ Battery optimization is still ON for Nomad. The heartbeat " +
                            "may be killed after a few hours. Tap below to disable it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(onClick = {
                        try {
                            // Direct request — Android shows a system dialog. Available since API 23.
                            @Suppress("BatteryLife")
                            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${ctx.packageName}"))
                            ctx.startActivity(i)
                        } catch (t: Throwable) {
                            // Some OEMs block the direct request — fall back to settings page.
                            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }) { Text("Disable battery optimization") }
                } else if (heartbeatOn) {
                    Text(
                        "✅ Battery optimization disabled for Nomad.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Relay server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("Relay base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text("Account key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = url.isNotBlank() && key.isNotBlank(),
                    onClick = {
                        app.prefs.relayBaseUrl = url.trimEnd('/')
                        app.prefs.accountKey = key
                        relaySaveMsg = "Saved."
                    }
                ) { Text("Save") }
                relaySaveMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                val versionLabel = remember(ctx) {
                    try {
                        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                        @Suppress("DEPRECATION")
                        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                        "Nomad Travel ${pi.versionName} (build $code)"
                    } catch (t: Throwable) {
                        "Nomad Travel"
                    }
                }
                Text(
                    versionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Companion app for the Nomad SMS bridge. " +
                        "Receives and sends SMS through your paired home phone via Firebase Cloud Messaging. " +
                        "Calls use this phone's own SIM (the recipient sees this phone's number).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun ageLabel(ts: Long): String {
    val ageMs = System.currentTimeMillis() - ts
    val mins = ageMs / 60_000
    return when {
        ageMs < 60_000 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 24 * 60 -> "${mins / 60}h${mins % 60}m ago"
        else -> "at ${timeFmt.format(Date(ts))}"
    }
}
