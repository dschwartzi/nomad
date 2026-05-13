package ai.nomad.relay

import ai.nomad.NomadApp
import ai.nomad.bridge.ContactResolver
import ai.nomad.data.ConversationEntity
import ai.nomad.data.MessageEntity
import ai.nomad.shared.relay.RelayBodyChunker
import ai.nomad.shared.relay.RelayBodyReassembler
import ai.nomad.shared.relay.RelayClient
import ai.nomad.shared.relay.RelayContact
import ai.nomad.shared.relay.RelayHistoryItem
import ai.nomad.shared.relay.RelayMessage
import ai.nomad.sms.SmsSender
import ai.nomad.util.Logger
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Handles inbound relay payloads on the **Home (H)** side.
 * The Travel app sends commands; H executes (SMS, contact lookup, history) and replies via [RelayClient.send].
 */
object RelayHandler {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(Dispatchers.IO)
    private val reassembler = RelayBodyReassembler()

    /** Called by [NomadFcmService] when an FCM message arrives on H. */
    fun onPayload(context: Context, payloadJson: String) {
        val app = context.applicationContext as NomadApp
        val raw = try {
            json.decodeFromString(RelayMessage.serializer(), payloadJson)
        } catch (t: Throwable) {
            Logger.w("Bad relay payload: ${t.message}")
            return
        }
        // Any inbound payload from the relay means the Travel app is alive — record
        // this even for in-progress chunks. Used for offline-detection / SMS fallback.
        app.prefs.lastSeenTravelAt = System.currentTimeMillis()

        // Buffer multi-part body chunks until the full message is reconstructed.
        val msg = reassembler.feed(raw) ?: run {
            Logger.i("Relay <- ${raw.type} part ${raw.chunkIndex}/${raw.totalChunks} (buffered)")
            return
        }
        Logger.i("Relay <- ${msg.type}")
        scope.launch {
            try {
                when (msg.type) {
                    RelayMessage.Type.SMS_OUT -> handleSmsOut(context, app, msg)
                    RelayMessage.Type.HISTORY_REQUEST -> handleHistoryRequest(app, msg)
                    RelayMessage.Type.CONTACTS_REQUEST -> handleContactsRequest(context, app)
                    RelayMessage.Type.PING -> sendOnH(app, RelayMessage(type = RelayMessage.Type.PONG))
                    else -> Logger.w("Unhandled relay type: ${msg.type}")
                }
            } catch (t: Throwable) {
                Logger.e("RelayHandler error for ${msg.type}", t)
            }
        }
    }

    private suspend fun handleSmsOut(context: Context, app: NomadApp, msg: RelayMessage) {
        val address = msg.address?.takeIf { it.isNotBlank() }
        val body = msg.body?.takeIf { it.isNotBlank() }
        val clientId = msg.clientId
        if (address == null || body == null) {
            sendOnH(app, RelayMessage(
                type = RelayMessage.Type.SEND_STATUS,
                clientId = clientId, ok = false, error = "missing address or body"
            ))
            return
        }
        val result = SmsSender.send(context, address, body)
        if (result.isSuccess) {
            // Record outbound in our DB
            val ts = System.currentTimeMillis()
            app.db.messageDao().insert(
                MessageEntity(address = address, body = body, timestamp = ts, direction = MessageEntity.OUT)
            )
            val existing = app.db.conversationDao().get(address)
            app.db.conversationDao().upsert(
                ConversationEntity(
                    address = address,
                    displayName = existing?.displayName ?: ContactResolver.nameFor(context, address),
                    lastMessageTime = ts,
                    lastMessagePreview = body.take(120),
                    unread = existing?.unread ?: 0
                )
            )
            app.prefs.activeThreadAddress = address
        }
        sendOnH(app, RelayMessage(
            type = RelayMessage.Type.SEND_STATUS,
            clientId = clientId,
            ok = result.isSuccess,
            error = result.exceptionOrNull()?.message,
            address = address,
            ts = System.currentTimeMillis()
        ))
    }

    private suspend fun handleHistoryRequest(app: NomadApp, msg: RelayMessage) {
        val address = msg.address ?: return
        val limit = (msg.limit ?: 50).coerceIn(1, 100)
        val recent = app.db.messageDao().recentFor(address, limit)
        val items = recent.map {
            // Per-item bodies can't be split across chunks (they're nested in a
            // list), so truncate anything that would by itself blow the
            // chunk budget. Real-time SMS uses full body chunking elsewhere.
            val safe = if (it.body.length > 2500) it.body.take(2500) + "…[truncated, see home phone]" else it.body
            RelayHistoryItem(body = safe, ts = it.timestamp, direction = it.direction)
        }
        // SMS bodies can approach 160 chars each and users can pull up to 100
        // messages. Chunk by estimated serialized size so we never hit FCM's
        // 4 KB data-message limit (relay itself rejects >3500 bytes).
        val chunks = chunkBySize(items, budgetBytes = 2800) { it.body.length + 48 }
        val total = chunks.size.coerceAtLeast(1)
        if (chunks.isEmpty()) {
            sendOnH(app, RelayMessage(
                type = RelayMessage.Type.HISTORY_RESPONSE,
                address = address, messages = emptyList(),
                chunkIndex = 0, totalChunks = 1
            ))
            return
        }
        chunks.forEachIndexed { idx, chunk ->
            sendOnH(app, RelayMessage(
                type = RelayMessage.Type.HISTORY_RESPONSE,
                address = address,
                messages = chunk,
                chunkIndex = idx,
                totalChunks = total
            ))
        }
    }

    private suspend fun handleContactsRequest(context: Context, app: NomadApp) {
        val contacts = readAllContacts(context)
        if (contacts.isEmpty()) {
            sendOnH(app, RelayMessage(
                type = RelayMessage.Type.CONTACTS_RESPONSE,
                contacts = emptyList(), chunkIndex = 0, totalChunks = 1
            ))
            return
        }
        // Greedy size-based chunking — previous fixed 60-per-chunk could exceed
        // the 3500 byte per-message limit when names/numbers were long.
        val chunks = chunkBySize(contacts, budgetBytes = 2800) { c ->
            c.name.length + c.numbers.sumOf { it.length + 3 } + 16
        }
        chunks.forEachIndexed { idx, chunk ->
            sendOnH(app, RelayMessage(
                type = RelayMessage.Type.CONTACTS_RESPONSE,
                contacts = chunk,
                chunkIndex = idx,
                totalChunks = chunks.size
            ))
        }
    }

    /** Greedy packer: splits [items] into chunks whose total [estimate] fits
     *  under [budgetBytes]. A single item larger than the budget lives in its
     *  own chunk (will still fail, but nothing we can do without truncation). */
    private inline fun <T> chunkBySize(
        items: List<T>,
        budgetBytes: Int,
        estimate: (T) -> Int
    ): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val out = mutableListOf<MutableList<T>>()
        var cur = mutableListOf<T>()
        var curSize = 0
        for (item in items) {
            val s = estimate(item)
            if (cur.isNotEmpty() && curSize + s > budgetBytes) {
                out.add(cur)
                cur = mutableListOf()
                curSize = 0
            }
            cur.add(item)
            curSize += s
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun readAllContacts(context: Context): List<RelayContact> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return emptyList()

        val byContactId = LinkedHashMap<Long, MutableList<String>>()
        val nameById = HashMap<Long, String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val cid = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val number = c.getString(2)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    nameById[cid] = name
                    byContactId.getOrPut(cid) { mutableListOf() }.add(number)
                }
            }
        } catch (t: Throwable) {
            Logger.w("Contacts read failed: ${t.message}")
            return emptyList()
        }
        return byContactId.entries.map { (cid, nums) ->
            RelayContact(name = nameById[cid] ?: "(no name)", numbers = nums.distinct())
        }
    }

    /** Convenience: send a payload from H to T. Automatically splits oversized
     *  bodies into chunked parts that the receiver reassembles transparently. */
    suspend fun sendOnH(app: NomadApp, msg: RelayMessage) {
        val prefs = app.prefs
        if (!prefs.isRelayPaired()) return
        val client = RelayClient(prefs.relayBaseUrl, prefs.relayAccountKey)
        for (part in RelayBodyChunker.split(msg)) {
            val res = client.send(prefs.relayPairingId, prefs.relaySecret, prefs.relayRole, part)
            if (res.isFailure) {
                Logger.w("Relay send failed: ${res.exceptionOrNull()?.message}")
                return
            }
        }
    }
}
