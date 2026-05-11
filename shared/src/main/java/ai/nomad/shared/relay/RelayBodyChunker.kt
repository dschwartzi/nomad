package ai.nomad.shared.relay

import java.util.UUID

/**
 * Splits a single [RelayMessage] whose `body` exceeds the per-FCM-message
 * payload budget into multiple parts. All envelope metadata (address, name,
 * ts, clientId, etc.) is preserved on every part; only `body` is sliced.
 *
 * Receivers should hand every inbound [RelayMessage] to a [RelayBodyReassembler]
 * which transparently buffers chunked parts and emits a single reassembled
 * message when all parts have arrived.
 */
object RelayBodyChunker {

    /** Default body part size in characters. Leaves slack inside the ~3500-byte
     *  relay limit for the envelope (type, address, name, partKey, etc.). */
    const val DEFAULT_BODY_PART_CHARS = 2500

    /** Returns [msg] as-is when the body is small, or a list of N parts each
     *  carrying ~[partChars] of the body plus the original envelope fields. */
    fun split(msg: RelayMessage, partChars: Int = DEFAULT_BODY_PART_CHARS): List<RelayMessage> {
        val body = msg.body
        if (body == null || body.length <= partChars) return listOf(msg)

        val key = UUID.randomUUID().toString()
        val parts = body.chunked(partChars)
        return parts.mapIndexed { idx, slice ->
            msg.copy(
                body = slice,
                partKey = key,
                chunkIndex = idx,
                totalChunks = parts.size
            )
        }
    }
}

/**
 * Buffers multi-part [RelayMessage]s keyed by [RelayMessage.partKey] and
 * re-emits a single fully reassembled message when the last part arrives.
 * Single-part messages pass through untouched.
 *
 * Thread-safe via `synchronized`. Stale partial groups older than
 * [ttlMillis] are evicted opportunistically on each call.
 */
class RelayBodyReassembler(private val ttlMillis: Long = 5 * 60_000L) {

    private data class Pending(
        val parts: Array<String?>,
        val total: Int,
        val template: RelayMessage,
        var received: Int,
        val createdAt: Long
    )

    private val byKey = HashMap<String, Pending>()

    /**
     * Feed an inbound message. Returns:
     *  - the same [msg] if it isn't part of a multi-part group;
     *  - `null` if it's a chunk and we're still waiting for siblings;
     *  - a reassembled [RelayMessage] once the final chunk arrives.
     */
    @Synchronized
    fun feed(msg: RelayMessage): RelayMessage? {
        evictStaleLocked()

        val key = msg.partKey
        val total = msg.totalChunks ?: 0
        val idx = msg.chunkIndex ?: 0
        if (key.isNullOrBlank() || total <= 1) return msg

        val pending = byKey.getOrPut(key) {
            Pending(
                parts = arrayOfNulls(total),
                total = total,
                template = msg,
                received = 0,
                createdAt = System.currentTimeMillis()
            )
        }
        if (idx < 0 || idx >= pending.total) return null
        if (pending.parts[idx] != null) return null // duplicate part, ignore

        pending.parts[idx] = msg.body ?: ""
        pending.received += 1
        if (pending.received < pending.total) return null

        byKey.remove(key)
        val joined = buildString {
            for (p in pending.parts) append(p ?: "")
        }
        // Strip chunking metadata so downstream handlers don't trip on it.
        return pending.template.copy(
            body = joined,
            partKey = null,
            chunkIndex = null,
            totalChunks = null
        )
    }

    private fun evictStaleLocked() {
        val cutoff = System.currentTimeMillis() - ttlMillis
        val it = byKey.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.createdAt < cutoff) it.remove()
        }
    }
}
