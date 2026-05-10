package ai.nomad.travel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import ai.nomad.travel.data.TravelDb
import ai.nomad.travel.util.TravelPrefs
import com.google.firebase.messaging.FirebaseMessaging

class TravelApp : Application() {

    val db: TravelDb by lazy { TravelDb.get(this) }
    val prefs: TravelPrefs by lazy { TravelPrefs(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        warmFcmToken()
    }

    private fun warmFcmToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val t = task.result
                    if (!t.isNullOrBlank() && t != prefs.fcmToken) {
                        prefs.fcmToken = t
                        Log.i(TAG, "Cached FCM token (${t.take(12)}…)")
                    }
                } else {
                    Log.w(TAG, "FCM token fetch failed: ${task.exception?.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "FCM init failed: ${t.message}")
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING, "Incoming SMS",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    companion object {
        private const val TAG = "Nomad/Travel"
        const val CHANNEL_INCOMING = "incoming_sms"
        lateinit var instance: TravelApp
            private set
    }
}
