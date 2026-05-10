package ai.nomad.travel.ui

import ai.nomad.shared.relay.RelayMessage
import ai.nomad.travel.TravelApp
import ai.nomad.travel.data.TConversation
import ai.nomad.travel.relay.TravelRelay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ContactsScreen(
    app: TravelApp,
    padding: PaddingValues,
    onOpen: (address: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val all by app.db.contactDao().all().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var syncMsg by remember { mutableStateOf<String?>(null) }

    val filtered = remember(all, query) {
        if (query.isBlank()) all
        else all.filter { it.name.contains(query, ignoreCase = true) || it.numbers.contains(query) }
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search contacts") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                syncMsg = "Requesting from home phone…"
                scope.launch(Dispatchers.IO) {
                    val res = TravelRelay.send(app, RelayMessage(type = RelayMessage.Type.CONTACTS_REQUEST))
                    withContext(Dispatchers.Main) {
                        syncMsg = if (res.isSuccess) "Sync requested. Contacts will appear shortly."
                                  else "❌ ${res.exceptionOrNull()?.message}"
                    }
                }
            }) { Text("Sync from home") }
        }
        syncMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (all.isEmpty()) {
            Text(
                "No contacts yet. Tap \"Sync from home\" to fetch them from your home phone.",
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            return
        }

        LazyColumn {
            items(filtered, key = { it.id }) { contact ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(contact.name, fontWeight = FontWeight.SemiBold)
                        for (number in contact.numbers.split(",")) {
                            val num = number.trim()
                            if (num.isBlank()) continue
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(num, style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        val normalized = num.replace(Regex("[\\s().-]"), "")
                                        scope.launch(Dispatchers.IO) {
                                            // Ensure conversation exists
                                            if (app.db.conversationDao().get(normalized) == null) {
                                                app.db.conversationDao().upsert(TConversation(
                                                    address = normalized,
                                                    displayName = contact.name,
                                                    lastMessageTime = System.currentTimeMillis(),
                                                    lastMessagePreview = "",
                                                    unread = 0
                                                ))
                                            }
                                            withContext(Dispatchers.Main) { onOpen(normalized) }
                                        }
                                    }) { Text("SMS") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
