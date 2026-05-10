package ai.nomad.travel.ui

import ai.nomad.shared.relay.RelayMessage
import ai.nomad.travel.TravelApp
import ai.nomad.travel.data.TConversation
import ai.nomad.travel.data.TMessage
import ai.nomad.travel.relay.TravelRelay
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    app: TravelApp,
    address: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by app.db.messageDao().forAddress(address).collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(address) {
        displayName = withContext(Dispatchers.IO) {
            app.db.conversationDao().get(address)?.displayName
        }
        // Mark read + request history if empty
        withContext(Dispatchers.IO) {
            app.db.conversationDao().markRead(address)
            if (app.db.messageDao().countFor(address) == 0) {
                TravelRelay.send(app, RelayMessage(
                    type = RelayMessage.Type.HISTORY_REQUEST,
                    address = address,
                    limit = 50
                ))
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(displayName ?: address, style = MaterialTheme.typography.titleMedium)
                        if (displayName != null) Text(
                            address,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$address"))
                        ctx.startActivity(i)
                    }) {
                        Icon(Icons.Default.Phone, "Call (uses this phone's SIM)")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Message…") },
                        modifier = Modifier.weight(1f),
                        maxLines = 5
                    )
                    IconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isEmpty()) return@IconButton
                            input = ""
                            val cid = UUID.randomUUID().toString()
                            scope.launch(Dispatchers.IO) {
                                val ts = System.currentTimeMillis()
                                app.db.messageDao().insert(TMessage(
                                    address = address, body = text, timestamp = ts,
                                    direction = TMessage.PENDING, clientId = cid
                                ))
                                val existing = app.db.conversationDao().get(address)
                                app.db.conversationDao().upsert(TConversation(
                                    address = address,
                                    displayName = existing?.displayName ?: displayName,
                                    lastMessageTime = ts,
                                    lastMessagePreview = text.take(120),
                                    unread = 0
                                ))
                                TravelRelay.send(app, RelayMessage(
                                    type = RelayMessage.Type.SMS_OUT,
                                    address = address,
                                    body = text,
                                    clientId = cid
                                ))
                            }
                        },
                        enabled = input.isNotBlank()
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages, key = { it.id }) { m -> MessageBubble(m) }
        }
    }
}

@Composable
private fun MessageBubble(m: TMessage) {
    val isMine = m.direction != TMessage.IN
    val bubbleColor = when {
        m.direction == TMessage.PENDING -> MaterialTheme.colorScheme.surfaceVariant
        isMine -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(m.body, color = textColor)
                Text(
                    if (m.direction == TMessage.PENDING) "Sending…" else fmtTime(m.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f)
                )
            }
        }
    }
}
