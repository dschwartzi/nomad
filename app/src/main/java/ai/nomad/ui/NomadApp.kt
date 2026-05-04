package ai.nomad.ui

import ai.nomad.NomadApp
import ai.nomad.bridge.BridgeService
import ai.nomad.telegram.TelegramBot
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
            CheckRow("Telegram bot configured", configured) {
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

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
