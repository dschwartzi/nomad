package ai.nomad.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Required stub service for default SMS app role.
 * Handles ACTION_RESPOND_VIA_MESSAGE (reply-from-car / lockscreen quick reply).
 * Currently a no-op; reply funnel is via Telegram.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
