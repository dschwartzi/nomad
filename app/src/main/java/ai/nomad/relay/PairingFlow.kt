package ai.nomad.relay

import ai.nomad.NomadApp
import ai.nomad.shared.relay.RelayClient
import ai.nomad.util.Logger
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * H-side pairing helpers. T-side equivalent lives in the :travel module.
 */
object PairingFlow {

    /** Ensure we have a fresh FCM token. Falls back to the cached one. */
    suspend fun fetchFcmToken(app: NomadApp): String? {
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
        } catch (e: Throwable) {
            Logger.w("fetchFcmToken failed: ${e.message}")
            app.prefs.fcmToken.takeIf { it.isNotBlank() }
        }
    }

    /**
     * On Home: start pairing, get a code to display, then poll until the travel side completes.
     * Returns true if paired successfully within [timeoutMs], else false.
     *
     * Calls [onCode] with the 6-digit code as soon as it's known.
     */
    suspend fun runHomePairing(
        app: NomadApp,
        onCode: (code: String) -> Unit,
        timeoutMs: Long = 9 * 60 * 1000L
    ): Result<Unit> {
        if (!app.prefs.hasRelayCredentials()) {
            return Result.failure(IllegalStateException("Relay URL and account key required"))
        }
        val token = fetchFcmToken(app) ?: return Result.failure(IllegalStateException("No FCM token"))
        val client = RelayClient(app.prefs.relayBaseUrl, app.prefs.relayAccountKey)
        val start = client.pairStart(token, role = "home").getOrElse { return Result.failure(it) }
        onCode(start.code)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            val status = client.pairStatus(start.pairingId, start.secret, role = "home").getOrNull()
            if (!status.isNullOrBlank()) {
                app.prefs.relayPairingId = start.pairingId
                app.prefs.relaySecret = start.secret
                app.prefs.relayRole = "home"
                Logger.i("Paired! pairingId=${start.pairingId}")
                return Result.success(Unit)
            }
        }
        return Result.failure(IllegalStateException("Timed out waiting for travel device"))
    }

}
