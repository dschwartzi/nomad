package ai.nomad.travel.ui

import ai.nomad.travel.TravelApp
import ai.nomad.travel.relay.PairingFlowT
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(app: TravelApp, onPaired: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    var url by remember { mutableStateOf(app.prefs.relayBaseUrl) }
    var key by remember { mutableStateOf(app.prefs.accountKey) }
    val credsReady = url.isNotBlank() && key.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Nomad", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("Pair with your home phone", style = MaterialTheme.typography.titleMedium)

        Text(
            "1. Enter the relay URL and account key (same values your home phone uses).\n" +
                "2. On home: Settings → \"Travel device pairing\" → Start pairing.\n" +
                "3. Type the 6-digit code here and tap Pair.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim() },
            label = { Text("Relay base URL") },
            placeholder = { Text("https://us-central1-…cloudfunctions.net") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("Account key") },
            placeholder = { Text("Same key as on the home phone") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter(Char::isDigit).take(6) },
            label = { Text("Pairing code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = code.length == 6 && credsReady && !busy,
            onClick = {
                // Persist creds before trying to pair.
                app.prefs.relayBaseUrl = url.trimEnd('/')
                app.prefs.accountKey = key
                busy = true
                msg = "Pairing…"
                scope.launch {
                    val res = PairingFlowT.finish(app, code)
                    busy = false
                    if (res.isSuccess) {
                        onPaired()
                    } else {
                        msg = "❌ ${res.exceptionOrNull()?.message ?: "pairing failed"}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Pairing…" else "Pair") }
        if (!credsReady) {
            Text(
                "Enter relay URL and account key to enable pairing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
