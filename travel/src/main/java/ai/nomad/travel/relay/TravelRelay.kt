package ai.nomad.travel.relay

import ai.nomad.shared.relay.RelayClient
import ai.nomad.shared.relay.RelayMessage
import ai.nomad.travel.TravelApp

/** Convenience wrapper that knows about [TravelPrefs]. */
object TravelRelay {

    fun client(app: TravelApp): RelayClient {
        require(app.prefs.hasRelayCredentials()) { "Relay URL and account key not set" }
        return RelayClient(app.prefs.relayBaseUrl, app.prefs.accountKey)
    }

    suspend fun send(app: TravelApp, msg: RelayMessage): Result<Unit> {
        if (!app.prefs.isPaired()) return Result.failure(IllegalStateException("Not paired"))
        return client(app).send(
            pairingId = app.prefs.pairingId,
            secret = app.prefs.secret,
            role = "travel",
            payload = msg
        )
    }
}
