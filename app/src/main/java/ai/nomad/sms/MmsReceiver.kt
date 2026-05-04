package ai.nomad.sms

import ai.nomad.util.Logger
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Stub MMS receiver. Required for default-SMS-app eligibility.
 * MMS handling not implemented yet (Phase 2+).
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.i("MMS received (stub, not processed): ${intent.action}")
    }
}
