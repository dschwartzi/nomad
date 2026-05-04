package ai.nomad.sms

import ai.nomad.NomadApp
import ai.nomad.data.ConversationEntity
import ai.nomad.data.MessageEntity
import ai.nomad.util.Logger
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager

object SmsSender {

    /**
     * Send an SMS from this phone's SIM. Handles multipart for long messages.
     * Also writes a copy into the system "sent" folder (required of default SMS apps)
     * and into our own DB.
     */
    suspend fun send(context: Context, address: String, body: String): Result<Unit> {
        if (address.isBlank() || body.isEmpty()) {
            return Result.failure(IllegalArgumentException("empty address or body"))
        }
        return try {
            val manager = smsManager(context)
            val parts: ArrayList<String> = manager.divideMessage(body)
            if (parts.size == 1) {
                manager.sendTextMessage(address, null, parts[0], null, null)
            } else {
                manager.sendMultipartTextMessage(address, null, parts, null, null)
            }
            Logger.i("SMS out to $address: ${body.take(60)} (${parts.size} parts)")
            recordSent(context, address, body)
            Result.success(Unit)
        } catch (t: Throwable) {
            Logger.e("sendTextMessage failed", t)
            Result.failure(t)
        }
    }

    private fun smsManager(context: Context): SmsManager {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private suspend fun recordSent(context: Context, address: String, body: String) {
        val app = context.applicationContext as NomadApp
        val now = System.currentTimeMillis()
        app.db.messageDao().insert(
            MessageEntity(
                address = address,
                body = body,
                timestamp = now,
                direction = MessageEntity.OUT
            )
        )
        val existing = app.db.conversationDao().get(address)
        app.db.conversationDao().upsert(
            ConversationEntity(
                address = address,
                displayName = existing?.displayName,
                lastMessageTime = now,
                lastMessagePreview = body.take(120),
                unread = existing?.unread ?: 0
            )
        )
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            }
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        } catch (t: Throwable) {
            Logger.w("Could not write to system SMS sent folder: ${t.message}")
        }
    }
}
