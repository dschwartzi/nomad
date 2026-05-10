package ai.nomad.relay

import ai.nomad.NomadApp
import ai.nomad.shared.relay.RelayClient
import ai.nomad.util.Logger
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives FCM data messages from the Nomad relay.
 * Same class is reused on H and T phones — dispatch is by `relayRole` in Prefs.
 */
class NomadFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Logger.i("FCM token refreshed")
        val app = applicationContext as NomadApp
        app.prefs.fcmToken = token

        // If paired, push the new token to the relay so the partner can still reach us.
        if (app.prefs.isRelayPaired()) {
            CoroutineScope(Dispatchers.IO).launch {
                val client = RelayClient(app.prefs.relayBaseUrl, app.prefs.relayAccountKey)
                client.updateToken(
                    pairingId = app.prefs.relayPairingId,
                    secret = app.prefs.relaySecret,
                    role = app.prefs.relayRole,
                    token = token
                )
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = message.data["payload"]
        if (payload.isNullOrBlank()) {
            Logger.w("FCM message without payload")
            return
        }
        val app = applicationContext as NomadApp
        when (app.prefs.relayRole) {
            "home" -> RelayHandler.onPayload(applicationContext, payload)
            "travel" -> {
                // Travel app handles its own dispatch via TravelRelayHandler defined in :travel module.
                // Forward to a broadcast so the travel UI/service can react.
                val intent = android.content.Intent(ACTION_RELAY_PAYLOAD)
                intent.setPackage(packageName)
                intent.putExtra(EXTRA_PAYLOAD, payload)
                sendBroadcast(intent)
            }
        }
    }

    companion object {
        const val ACTION_RELAY_PAYLOAD = "ai.nomad.action.RELAY_PAYLOAD"
        const val EXTRA_PAYLOAD = "payload"
    }
}
