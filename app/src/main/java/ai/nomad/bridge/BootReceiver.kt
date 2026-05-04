package ai.nomad.bridge

import ai.nomad.NomadApp
import ai.nomad.util.Logger
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NomadApp
        if (app.prefs.bridgeEnabled && app.prefs.isConfigured()) {
            Logger.i("Boot: restarting bridge service")
            BridgeService.start(context)
        }
    }
}
