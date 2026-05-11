package ai.nomad.travel.relay

import ai.nomad.shared.relay.RelayBodyChunker
import ai.nomad.shared.relay.RelayClient
import ai.nomad.shared.relay.RelayMessage
import ai.nomad.travel.TravelApp

/** Convenience wrapper that knows about [TravelPrefs]. */
object TravelRelay {

    fun client(app: TravelApp): RelayClient {
        require(app.prefs.hasRelayCredentials()) { "Relay URL and account key not set" }
        return RelayClient(app.prefs.relayBaseUrl, app.prefs.accountKey)
    }

    /** Sends [msg], automatically splitting oversized bodies into multi-part
     *  chunks that the receiver reassembles transparently. Fails fast on the
     *  first part that can't be sent. */
    suspend fun send(app: TravelApp, msg: RelayMessage): Result<Unit> {
        if (!app.prefs.isPaired()) return Result.failure(IllegalStateException("Not paired"))
        val c = client(app)
        for (part in RelayBodyChunker.split(msg)) {
            val res = c.send(
                pairingId = app.prefs.pairingId,
                secret = app.prefs.secret,
                role = "travel",
                payload = part
            )
            if (res.isFailure) return res
        }
        return Result.success(Unit)
    }
}
