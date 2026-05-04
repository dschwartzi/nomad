package ai.nomad

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ai.nomad.data.NomadDatabase
import ai.nomad.util.Prefs

class NomadApp : Application() {

    val db: NomadDatabase by lazy { NomadDatabase.get(this) }
    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BRIDGE, "Bridge",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS, "Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    companion object {
        const val CHANNEL_BRIDGE = "bridge"
        const val CHANNEL_ALERTS = "alerts"
        lateinit var instance: NomadApp
            private set
    }
}
