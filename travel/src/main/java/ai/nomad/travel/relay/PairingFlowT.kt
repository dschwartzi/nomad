package ai.nomad.travel.relay

import ai.nomad.shared.relay.RelayClient
import ai.nomad.travel.TravelApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Travel-side pairing helpers. */
object PairingFlowT {

    private suspend fun fetchFcmToken(app: TravelApp): String? {
        return try {
            val t = suspendCoroutine<String?> { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
            if (!t.isNullOrBlank()) {
                app.prefs.fcmToken = t
                t
            } else app.prefs.fcmToken.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            app.prefs.fcmToken.takeIf { it.isNotBlank() }
        }
    }

    suspend fun finish(app: TravelApp, code: String): Result<Unit> {
        if (!app.prefs.hasRelayCredentials()) {
            return Result.failure(IllegalStateException("Set the relay URL and account key first"))
        }
        val token = fetchFcmToken(app) ?: return Result.failure(IllegalStateException("No FCM token. Try again in a moment."))
        val client = TravelRelay.client(app)
        val res = client.pairFinish(code.trim(), token, role = "travel")
        if (res.isFailure) return Result.failure(res.exceptionOrNull()!!)
        val finish = res.getOrThrow()
        app.prefs.pairingId = finish.pairingId
        app.prefs.secret = finish.secret
        return Result.success(Unit)
    }
}
