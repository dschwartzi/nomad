package ai.nomad.sms

import ai.nomad.NomadApp
import ai.nomad.bridge.BridgeService
import ai.nomad.data.ConversationEntity
import ai.nomad.data.MessageEntity
import ai.nomad.util.Logger
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when an SMS is delivered to this phone.
 * Only the *default* SMS app receives SMS_DELIVER_ACTION.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Group multipart by originating address (SMS over 160 chars comes as multiple parts)
        val grouped = messages.groupBy { it.originatingAddress ?: "unknown" }

        val app = context.applicationContext as NomadApp
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                for ((address, parts) in grouped) {
                    val body = parts.joinToString("") { it.displayMessageBody ?: "" }
                    val ts = parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                    handleIncoming(context, app, address, body, ts)
                }
            } catch (t: Throwable) {
                Logger.e("SmsDeliverReceiver failure", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleIncoming(
        context: Context,
        app: NomadApp,
        address: String,
        body: String,
        timestamp: Long
    ) {
        Logger.i("SMS in from $address: ${body.take(60)}")

        // 1. Write to the system SMS provider (as default SMS app, we're responsible for this)
        writeToSystemInbox(context, address, body, timestamp)

        // 2. Write to our own DB
        val msgId = app.db.messageDao().insert(
            MessageEntity(
                address = address,
                body = body,
                timestamp = timestamp,
                direction = MessageEntity.IN
            )
        )

        val existing = app.db.conversationDao().get(address)
        app.db.conversationDao().upsert(
            ConversationEntity(
                address = address,
                displayName = existing?.displayName,
                lastMessageTime = timestamp,
                lastMessagePreview = body.take(120),
                unread = (existing?.unread ?: 0) + 1
            )
        )

        // 3. Forward to Telegram via the bridge service
        BridgeService.notifyInboundSms(context, msgId, address, body, timestamp)
    }

    private fun writeToSystemInbox(
        context: Context,
        address: String,
        body: String,
        timestamp: Long
    ) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (t: Throwable) {
            Logger.w("Could not write to system SMS inbox: ${t.message}")
        }
    }
}
