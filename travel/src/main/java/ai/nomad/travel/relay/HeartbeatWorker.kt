package ai.nomad.travel.relay

import ai.nomad.shared.relay.RelayMessage
import ai.nomad.travel.TravelApp
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodically sends a PING to the Home app over the relay so that the Home
 * side knows the Travel app is alive. The Home app uses this signal to decide
 * whether to additionally route inbound SMS via the SMS fallback path.
 *
 * Runs every [HEARTBEAT_INTERVAL_MIN] minutes whenever the Travel app is
 * paired, has relay credentials, and the device has any network. Android may
 * coalesce or delay periodic work; for our offline-detection heuristic, an
 * occasional 5–15 minute drift is fine — it just means the Home phone may
 * sometimes send a redundant fallback SMS.
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TravelApp
        if (!app.prefs.isPaired() || !app.prefs.hasRelayCredentials()) {
            Log.i(TAG, "Heartbeat skipped: not paired / no creds")
            return Result.success()
        }
        val res = TravelRelay.send(app, RelayMessage(type = RelayMessage.Type.PING))
        return if (res.isSuccess) {
            Log.i(TAG, "Heartbeat PING sent")
            Result.success()
        } else {
            Log.w(TAG, "Heartbeat PING failed: ${res.exceptionOrNull()?.message}")
            // Retry per WorkManager defaults — the OS will back off and try again.
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "Nomad/Heartbeat"
        private const val UNIQUE_NAME = "nomad_travel_heartbeat"

        /** WorkManager's minimum periodic interval is 15 minutes. We pick the
         *  minimum so the Home app's offline-detection has the freshest data
         *  possible without a foreground service. */
        const val HEARTBEAT_INTERVAL_MIN: Long = 15

        /** Schedule (or re-schedule) the periodic heartbeat. Idempotent: safe
         *  to call on every app start. Uses KEEP so we don't reset the timer
         *  on hot restarts. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                HEARTBEAT_INTERVAL_MIN, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Heartbeat scheduled every ${HEARTBEAT_INTERVAL_MIN}m")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
