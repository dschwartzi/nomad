package ai.nomad.travel.relay

import ai.nomad.shared.relay.RelayMessage
import ai.nomad.travel.MainActivity
import ai.nomad.travel.R
import ai.nomad.travel.TravelApp
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that keeps the Travel app reachable by the Home phone.
 *
 * Why we need this: WorkManager periodic jobs are routinely killed by aggressive
 * Android OEM battery managers (Cellcom-grade carriers, Xiaomi/Huawei MIUI/EMUI,
 * Samsung adaptive battery, etc). When the user swipes the app out of recents
 * the WorkManager job can be force-stopped indefinitely. The only reliable way
 * to keep a long-running background task alive on modern Android is a
 * foreground service with a persistent notification.
 *
 * The service:
 *  - Posts a low-importance, ongoing notification ("Connected to home phone").
 *  - Sends a PING to Home every [HEARTBEAT_INTERVAL_MIN] minutes so Home knows
 *    we're alive (used by Home's SMS-fallback heuristic).
 *  - Updates the notification with the last successful round-trip time.
 *  - Restarts itself on device reboot via [BootReceiver].
 *
 * The user can disable it from Travel Settings (which also stops the
 * notification). With it disabled, only the WorkManager-based heartbeat (15-min
 * minimum, OS may delay) runs — appropriate for users who don't care about
 * sub-15-min freshness.
 */
class HeartbeatService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // Must call startForeground within a few seconds of onStartCommand or
        // Android will kill us with a ForegroundServiceDidNotStartInTimeException.
        val notif = buildNotification(this, statusText(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
        loopJob = scope.launch { heartbeatLoop() }
        Log.i(TAG, "HeartbeatService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if Android kills us under memory pressure, please
        // restart us when conditions allow. (No guarantee on heavily battery-
        // optimized devices, but it's the right hint.)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "HeartbeatService stopping")
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun heartbeatLoop() {
        // Send a PING immediately on start so Home learns about us within
        // seconds of the app being opened, then settle into the periodic
        // cadence.
        sendPingAndUpdateNotification()
        while (scope.isActive) {
            delay(HEARTBEAT_INTERVAL_MIN * 60_000L)
            sendPingAndUpdateNotification()
        }
    }

    private suspend fun sendPingAndUpdateNotification() {
        val app = applicationContext as TravelApp
        if (!app.prefs.isPaired() || !app.prefs.hasRelayCredentials()) {
            updateNotification(this, "Not paired with a home phone")
            return
        }
        val res = TravelRelay.send(app, RelayMessage(type = RelayMessage.Type.PING))
        if (res.isSuccess) {
            app.prefs.lastHeartbeatSentAt = System.currentTimeMillis()
            Log.i(TAG, "Heartbeat PING sent")
        } else {
            Log.w(TAG, "Heartbeat PING failed: ${res.exceptionOrNull()?.message}")
        }
        updateNotification(this, statusText(this))
    }

    companion object {
        private const val TAG = "Nomad/Heartbeat"
        const val CHANNEL_ID = "nomad_travel_connection"
        const val NOTIFICATION_ID = 4242
        const val HEARTBEAT_INTERVAL_MIN: Long = 5

        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        /** Idempotent: starts the service if [TravelPrefs.heartbeatServiceEnabled]. */
        fun startIfEnabled(context: Context) {
            val app = context.applicationContext as TravelApp
            if (!app.prefs.heartbeatServiceEnabled) return
            val intent = Intent(context, HeartbeatService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeartbeatService::class.java))
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Connection status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Nomad reachable by your home phone in the background."
                setShowBadge(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }

        private fun statusText(context: Context): String {
            val app = context.applicationContext as TravelApp
            val lastPong = app.prefs.lastPongAt
            val lastSent = app.prefs.lastHeartbeatSentAt
            return when {
                !app.prefs.isPaired() -> "Not paired with a home phone"
                lastPong > 0 -> "Connected · last reply ${timeFmt.format(Date(lastPong))}"
                lastSent > 0 -> "Sent ping at ${timeFmt.format(Date(lastSent))} · waiting for reply"
                else -> "Connecting…"
            }
        }

        private fun buildNotification(context: Context, text: String): android.app.Notification {
            val tap = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Nomad Travel")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(tap)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        private fun updateNotification(context: Context, text: String) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, buildNotification(context, text))
        }
    }
}
