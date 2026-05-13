package ai.nomad.ui

import ai.nomad.NomadApp
import ai.nomad.bridge.BridgeService
import ai.nomad.relay.PairingFlow
import ai.nomad.telegram.TelegramBot
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String) { Status("Status"), Inbox("Inbox"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NomadApp(
    app: NomadApp,
    hasPermissions: MutableState<Boolean>,
    isDefaultSms: MutableState<Boolean>,
    requestPermissions: () -> Unit,
    requestDefaultSms: () -> Unit,
) {
    var tab by remember {
        mutableStateOf(if (app.prefs.isConfigured()) Tab.Status else Tab.Settings)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Nomad") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Status,
                    onClick = { tab = Tab.Status },
                    icon = { Icon(Icons.Default.Info, null) },
                    label = { Text("Status") }
                )
                NavigationBarItem(
                    selected = tab == Tab.Inbox,
                    onClick = { tab = Tab.Inbox },
                    icon = { Icon(Icons.Default.Chat, null) },
                    label = { Text("Inbox") }
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings,
                    onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        when (tab) {
            Tab.Status -> StatusScreen(
                app, hasPermissions, isDefaultSms,
                requestPermissions, requestDefaultSms, padding
            )
            Tab.Inbox -> InboxScreen(app, padding)
            Tab.Settings -> SettingsScreen(app, padding)
        }
    }
}

@Composable
private fun StatusScreen(
    app: NomadApp,
    hasPermissions: MutableState<Boolean>,
    isDefaultSms: MutableState<Boolean>,
    requestPermissions: () -> Unit,
    requestDefaultSms: () -> Unit,
    padding: PaddingValues,
) {
    val context = LocalContext.current
    var bridgeOn by remember { mutableStateOf(app.prefs.bridgeEnabled) }
    val configured = app.prefs.isConfigured()

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard("Setup checklist") {
            CheckRow("SMS permissions granted", hasPermissions.value) {
                OutlinedButton(onClick = requestPermissions) { Text("Grant") }
            }
            CheckRow("Nomad is default SMS app", isDefaultSms.value) {
                OutlinedButton(onClick = requestDefaultSms) { Text("Set") }
            }
            CheckRow("Transport configured (Telegram or paired travel app)", configured) {
                Text("Go to Settings", style = MaterialTheme.typography.labelMedium)
            }
        }

        SectionCard("Bridge") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (bridgeOn) "Bridge running" else "Bridge stopped",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (bridgeOn) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = bridgeOn,
                    enabled = hasPermissions.value && isDefaultSms.value && configured,
                    onCheckedChange = { on ->
                        bridgeOn = on
                        app.prefs.bridgeEnabled = on
                        if (on) BridgeService.start(context) else BridgeService.stop(context)
                    }
                )
            }
            if (!hasPermissions.value || !isDefaultSms.value || !configured) {
                Text(
                    "Complete the checklist above before starting the bridge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard("Recent events") {
            val events by app.db.eventDao().recent(20).collectAsState(initial = emptyList())
            if (events.isEmpty()) {
                Text("No events yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                for (e in events.take(10)) {
                    Text(
                        "${fmtTime(e.timestamp)}  ${e.message}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxScreen(app: NomadApp, padding: PaddingValues) {
    val convs by app.db.conversationDao().all().collectAsState(initial = emptyList())
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 12.dp)
    ) {
        items(convs) { c ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                colors = CardDefaults.cardColors()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        c.displayName ?: c.address,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        c.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2
                    )
                    Text(
                        fmtTime(c.lastMessageTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (convs.isEmpty()) {
            items(listOf(Unit)) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "No conversations yet. Once SMS arrives, it'll show up here.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(app: NomadApp, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(app.prefs.telegramBotToken) }
    var chatId by remember { mutableStateOf(app.prefs.telegramChatId) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    var trustedNums by remember { mutableStateOf(app.prefs.trustedTravelNumbersRaw) }
    var smsPrefix by remember { mutableStateOf(app.prefs.smsCommandPrefix) }
    var smsFallback by remember { mutableStateOf(app.prefs.smsFallbackEnabled) }
    var fallbackDest by remember { mutableStateOf(app.prefs.smsFallbackDestination) }
    var fallbackOfflineMin by remember { mutableStateOf(app.prefs.smsFallbackOfflineMinutes.toString()) }
    var fallbackAlways by remember { mutableStateOf(app.prefs.smsFallbackAlways) }

    var paired by remember { mutableStateOf(app.prefs.isRelayPaired()) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var pairingBusy by remember { mutableStateOf(false) }
    var pairingMsg by remember { mutableStateOf<String?>(null) }

    var relayUrl by remember { mutableStateOf(app.prefs.relayBaseUrl) }
    var relayKey by remember { mutableStateOf(app.prefs.relayAccountKey) }
    var relaySaveMsg by remember { mutableStateOf<String?>(null) }
    val hasRelayCreds = relayUrl.isNotBlank() && relayKey.isNotBlank() &&
        app.prefs.relayBaseUrl == relayUrl.trim() && app.prefs.relayAccountKey == relayKey.trim()

    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard("Relay server") {
            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it.trim() },
                label = { Text("Relay base URL") },
                placeholder = { Text("https://us-central1-yourproj.cloudfunctions.net") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = relayKey,
                onValueChange = { relayKey = it.trim() },
                label = { Text("Account key") },
                placeholder = { Text("Shared secret, same on both phones") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = relayUrl.isNotBlank() && relayKey.isNotBlank(),
                onClick = {
                    app.prefs.relayBaseUrl = relayUrl.trim().trimEnd('/')
                    app.prefs.relayAccountKey = relayKey.trim()
                    relaySaveMsg = "Saved."
                }
            ) { Text("Save relay credentials") }
            relaySaveMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Type the URL of your Cloud Functions endpoint and the account key you configured " +
                    "on the relay server. Both must be identical on home and travel phones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard("Travel device pairing") {
            if (paired) {
                Text(
                    "✅ Paired with travel device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF10B981)
                )
                Text(
                    "Pairing ID: ${app.prefs.relayPairingId.take(8)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = {
                    app.prefs.clearRelayPairing()
                    paired = false
                    pairingCode = null
                    pairingMsg = "Unpaired."
                }) { Text("Unpair") }
            } else {
                if (!hasRelayCreds) {
                    Text(
                        "Set the relay URL and account key above (and tap Save) before pairing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "Install the Nomad Travel app on your travel phone, then tap below to start pairing. " +
                        "A 6-digit code will appear — enter it on the travel phone within 10 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                pairingCode?.let { code ->
                    Text(
                        code.toCharArray().joinToString(" "),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Enter this code on the travel phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    enabled = !pairingBusy && hasRelayCreds,
                    onClick = {
                        pairingBusy = true
                        pairingCode = null
                        pairingMsg = "Starting…"
                        scope.launch {
                            val res = PairingFlow.runHomePairing(
                                app,
                                onCode = { c -> pairingCode = c }
                            )
                            pairingBusy = false
                            if (res.isSuccess) {
                                paired = true
                                pairingMsg = "✅ Paired!"
                                pairingCode = null
                                BridgeService.start(app)
                            } else {
                                pairingMsg = "❌ ${res.exceptionOrNull()?.message}"
                            }
                        }
                    }
                ) { Text(if (pairingBusy) "Waiting for travel device…" else "Start pairing") }
            }
            pairingMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("Telegram bot") {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text("Bot token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = chatId,
                onValueChange = { chatId = it.trim() },
                label = { Text("Chat ID (your numeric Telegram user ID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    app.prefs.telegramBotToken = token
                    app.prefs.telegramChatId = chatId
                    testResult = "Saved."
                }) { Text("Save") }

                OutlinedButton(
                    enabled = token.isNotBlank() && chatId.isNotBlank() && !testing,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val bot = TelegramBot(token)
                            val username = withContext(Dispatchers.IO) { bot.getMe() }
                            testResult = if (username != null) {
                                withContext(Dispatchers.IO) {
                                    bot.sendMessage(chatId, "👋 <b>Nomad</b> connected.\nSend /help to begin.")
                                }
                                "Connected as @$username. Check Telegram for a test message."
                            } else {
                                "❌ Could not reach Telegram with this token."
                            }
                            testing = false
                        }
                    }
                ) { Text(if (testing) "Testing…" else "Test") }
            }
            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Get your bot token from @BotFather. Send /start to your bot, then " +
                    "open https://api.telegram.org/bot<TOKEN>/getUpdates in a browser " +
                    "to find your chat ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard("SMS fallback") {
            Text(
                "When the Travel app appears offline, the Home phone can also " +
                    "forward incoming SMS to a phone number of your choice " +
                    "(e.g. your travel SIM, or a Twilio / DID number that " +
                    "reaches you via another channel).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Last-seen indicator
            val lastSeen = app.prefs.lastSeenTravelAt
            val travelStatus = remember(lastSeen, fallbackOfflineMin) {
                if (lastSeen == 0L) "Travel app: never seen"
                else {
                    val ageMin = (System.currentTimeMillis() - lastSeen) / 60_000
                    val online = app.prefs.isTravelOnline()
                    "Travel app: " + (if (online) "online" else "offline") +
                        " (last seen ${ageMin}m ago)"
                }
            }
            Text(
                travelStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SMS fallback enabled",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Switch(checked = smsFallback, onCheckedChange = { smsFallback = it })
            }

            OutlinedTextField(
                value = fallbackDest,
                onValueChange = { fallbackDest = it.trim() },
                label = { Text("Fallback SMS number (e.g. Twilio/DID/travel SIM)") },
                placeholder = { Text("+15551234567") },
                singleLine = true,
                enabled = smsFallback,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = fallbackOfflineMin,
                onValueChange = { fallbackOfflineMin = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("Treat Travel as offline after N minutes") },
                placeholder = { Text("10") },
                singleLine = true,
                enabled = smsFallback && !fallbackAlways,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Always send fallback SMS (don't wait for offline)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Switch(
                    checked = fallbackAlways,
                    onCheckedChange = { fallbackAlways = it },
                    enabled = smsFallback
                )
            }

            HorizontalDivider()
            Text(
                "Trusted travel numbers (for #commands)",
                style = MaterialTheme.typography.titleSmall
            )
            OutlinedTextField(
                value = trustedNums,
                onValueChange = { trustedNums = it },
                label = { Text("Comma-separated") },
                placeholder = { Text("+1555..., +972..., ...") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = smsPrefix,
                onValueChange = { smsPrefix = it },
                label = { Text("Command prefix") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(onClick = {
                app.prefs.trustedTravelNumbersRaw = trustedNums
                app.prefs.smsCommandPrefix = smsPrefix.ifBlank { "#" }
                app.prefs.smsFallbackEnabled = smsFallback
                app.prefs.smsFallbackDestination = fallbackDest
                app.prefs.smsFallbackAlways = fallbackAlways
                fallbackOfflineMin.toIntOrNull()?.let { app.prefs.smsFallbackOfflineMinutes = it }
            }) { Text("Save SMS settings") }

            Text(
                "From a trusted travel number, text your home phone:\n" +
                    "${smsPrefix}send +15551234567 hello\n" +
                    "${smsPrefix}reply text  •  ${smsPrefix}to +1555... (or name)\n" +
                    "${smsPrefix}last [N]  •  ${smsPrefix}status  •  ${smsPrefix}help",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            (if (ok) "✅ " else "⬜ ") + label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp)
        )
        if (!ok) action()
    }
}

private fun fmtTime(epoch: Long): String =
    SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(epoch))
