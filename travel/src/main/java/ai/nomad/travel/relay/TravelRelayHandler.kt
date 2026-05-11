package ai.nomad.travel.relay

import ai.nomad.shared.relay.RelayContact
import ai.nomad.shared.relay.RelayMessage
import ai.nomad.travel.MainActivity
import ai.nomad.travel.R
import ai.nomad.travel.TravelApp
import ai.nomad.travel.data.TContact
import ai.nomad.travel.data.TConversation
import ai.nomad.travel.data.TMessage
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Handles inbound relay payloads on the **Travel (T)** side.
 * Persists messages, updates conversation list, surfaces notifications.
 *
 * In-memory accumulator for chunked contacts. Lost across process death — that's fine,
 * a new fetch on next launch starts over.
 */
object TravelRelayHandler {

    private const val TAG = "Nomad/Travel"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val contactsBuffer = mutableListOf<RelayContact>()
    private var contactsExpected = 0
    private var contactsSeen = 0

    fun onPayload(app: TravelApp, payloadJson: String) {
        val msg = try {
            json.decodeFromString(RelayMessage.serializer(), payloadJson)
        } catch (t: Throwable) {
            Log.w(TAG, "Bad payload: ${t.message}")
            return
        }
        Log.i(TAG, "T <- ${msg.type}")
        scope.launch {
            try {
                when (msg.type) {
                    RelayMessage.Type.SMS_IN -> handleSmsIn(app, msg)
                    RelayMessage.Type.SEND_STATUS -> handleSendStatus(app, msg)
                    RelayMessage.Type.HISTORY_RESPONSE -> handleHistoryResponse(app, msg)
                    RelayMessage.Type.CONTACTS_RESPONSE -> handleContactsResponse(app, msg)
                    RelayMessage.Type.PONG -> {
                        Log.i(TAG, "PONG")
                        app.prefs.lastPongAt = System.currentTimeMillis()
                    }
                    else -> Log.w(TAG, "Unknown payload type: ${msg.type}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Handler error for ${msg.type}", t)
            }
        }
    }

    private suspend fun handleSmsIn(app: TravelApp, msg: RelayMessage) {
        val address = msg.address ?: return
        val body = msg.body ?: return
        val ts = msg.ts ?: System.currentTimeMillis()

        app.db.messageDao().insert(TMessage(
            address = address,
            body = body,
            timestamp = ts,
            direction = TMessage.IN
        ))
        val existing = app.db.conversationDao().get(address)
        app.db.conversationDao().upsert(TConversation(
            address = address,
            displayName = existing?.displayName ?: msg.name,
            lastMessageTime = ts,
            lastMessagePreview = body.take(120),
            unread = (existing?.unread ?: 0) + 1
        ))
        notifyIncoming(app, msg.name ?: address, body)
    }

    private suspend fun handleSendStatus(app: TravelApp, msg: RelayMessage) {
        val cid = msg.clientId ?: return
        if (msg.ok == true) {
            app.db.messageDao().setDirectionByClient(cid, TMessage.OUT)
        } else {
            // Surface the failure inline by replacing the pending row's body with an error marker.
            // Simplest: delete pending row. UI shows nothing went out.
            app.db.messageDao().deleteByClient(cid)
            Log.w(TAG, "send failed: ${msg.error}")
        }
    }

    private suspend fun handleHistoryResponse(app: TravelApp, msg: RelayMessage) {
        val address = msg.address ?: return
        val items = msg.messages ?: return
        val idx = msg.chunkIndex ?: 0
        // Only the first chunk gets the duplicate-prevention check; follow-up
        // chunks must always be inserted or we'd lose the tail of the history.
        if (idx == 0 && app.db.messageDao().countFor(address) > 0) return
        for (it in items) {
            app.db.messageDao().insert(TMessage(
                address = address,
                body = it.body,
                timestamp = it.ts,
                direction = if (it.direction == 0) TMessage.IN else TMessage.OUT
            ))
        }
    }

    private suspend fun handleContactsResponse(app: TravelApp, msg: RelayMessage) {
        val total = msg.totalChunks ?: 1
        val idx = msg.chunkIndex ?: 0
        val chunk = msg.contacts ?: emptyList()

        if (idx == 0) {
            contactsBuffer.clear()
            contactsSeen = 0
            contactsExpected = total
        }
        contactsBuffer.addAll(chunk)
        contactsSeen++

        if (contactsSeen >= contactsExpected) {
            // All chunks received — replace local cache.
            val rows = contactsBuffer.map { c ->
                TContact(name = c.name, numbers = c.numbers.joinToString(","))
            }
            app.db.contactDao().clear()
            app.db.contactDao().insertAll(rows)
            Log.i(TAG, "Contacts synced: ${rows.size}")
            contactsBuffer.clear()
            contactsSeen = 0
            contactsExpected = 0
        }
    }

    private fun notifyIncoming(app: TravelApp, who: String, body: String) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val tap = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            app, 0, tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(app, TravelApp.CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(who)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(who.hashCode(), n)
    }
}
