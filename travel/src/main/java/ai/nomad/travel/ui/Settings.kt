package ai.nomad.travel.ui

import ai.nomad.shared.relay.RelayMessage
import ai.nomad.travel.TravelApp
import ai.nomad.travel.relay.TravelRelay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    app: TravelApp,
    padding: PaddingValues,
    onUnpair: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var pingMsg by remember { mutableStateOf<String?>(null) }

    var url by remember { mutableStateOf(app.prefs.relayBaseUrl) }
    var key by remember { mutableStateOf(app.prefs.accountKey) }
    var relaySaveMsg by remember { mutableStateOf<String?>(null) }

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
                Text(
                    "Nomad Travel — companion app for the Nomad SMS bridge. " +
                        "Receives and sends SMS through your paired home phone via Firebase Cloud Messaging. " +
                        "Calls use this phone's own SIM (the recipient sees this phone's number).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
