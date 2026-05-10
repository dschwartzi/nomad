package ai.nomad.travel.ui

import ai.nomad.shared.relay.RelayMessage
import ai.nomad.travel.TravelApp
import ai.nomad.travel.relay.TravelRelay
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab(val label: String) { Conv("Chats"), Contacts("Contacts"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravelRoot(app: TravelApp) {
    var paired by remember { mutableStateOf(app.prefs.isPaired()) }

    if (!paired) {
        OnboardingScreen(app, onPaired = {
            paired = true
            // Trigger initial contacts + history sync
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                TravelRelay.send(app, RelayMessage(type = RelayMessage.Type.CONTACTS_REQUEST))
            }
        })
        return
    }

    var openThread by remember { mutableStateOf<String?>(null) }
    if (openThread != null) {
        ThreadScreen(
            app = app,
            address = openThread!!,
            onBack = { openThread = null }
        )
        return
    }

    var tab by remember { mutableStateOf(Tab.Conv) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Nomad") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Conv, onClick = { tab = Tab.Conv },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, null) }, label = { Text("Chats") }
                )
                NavigationBarItem(
                    selected = tab == Tab.Contacts, onClick = { tab = Tab.Contacts },
                    icon = { Icon(Icons.Default.Contacts, null) }, label = { Text("Contacts") }
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings, onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        when (tab) {
            Tab.Conv -> ConversationsScreen(app, padding) { openThread = it }
            Tab.Contacts -> ContactsScreen(app, padding) { openThread = it }
            Tab.Settings -> SettingsScreen(
                app = app,
                padding = padding,
                onUnpair = { paired = false }
            )
        }
    }
}
