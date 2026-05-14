package ai.nomad.travel.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the heartbeat foreground service when the device boots or our
 * package is replaced. Without this, the user would have to manually open the
 * Travel app once after every reboot to get the heartbeat running again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "BootReceiver got ${intent.action}; starting heartbeat service")
        try {
            HeartbeatService.startIfEnabled(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start heartbeat service from boot: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "Nomad/Travel"
    }
}
