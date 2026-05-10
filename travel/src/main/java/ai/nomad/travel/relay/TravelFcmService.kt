package ai.nomad.travel.relay

import ai.nomad.shared.relay.RelayClient
import ai.nomad.travel.TravelApp
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TravelFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed")
        val app = applicationContext as TravelApp
        app.prefs.fcmToken = token
        if (app.prefs.isPaired() && app.prefs.hasRelayCredentials()) {
            CoroutineScope(Dispatchers.IO).launch {
                val client = TravelRelay.client(app)
                client.updateToken(
                    pairingId = app.prefs.pairingId,
                    secret = app.prefs.secret,
                    role = "travel",
                    token = token
                )
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = message.data["payload"]
        if (payload.isNullOrBlank()) {
            Log.w(TAG, "FCM message without payload")
            return
        }
        TravelRelayHandler.onPayload(applicationContext as TravelApp, payload)
    }

    companion object {
        private const val TAG = "Nomad/Travel"
    }
}
