package ai.nomad.bridge

import ai.nomad.MainActivity
import ai.nomad.NomadApp
import ai.nomad.telegram.TelegramBot
import ai.nomad.util.Logger
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that:
 *  - Long-polls Telegram for commands / replies
 *  - Provides a static hook (notifyInboundSms) the SMS receiver calls
 *  - Routes events through [BridgeRouter]
 */
class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var router: BridgeRouter? = null
    private var bot: TelegramBot? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Logger.i("BridgeService onStartCommand action=$action")

        startForeground(NOTIF_ID, buildNotification("Nomad bridge running"))

        val app = applicationContext as NomadApp
        val token = app.prefs.telegramBotToken
        val telegramOn = token.isNotBlank()
        val relayOn = app.prefs.isRelayPaired()

        if (!telegramOn && !relayOn) {
            updateNotif("Bridge inactive — no transport configured (Telegram or pairing)")
            return START_STICKY
        }

        if (telegramOn && bot == null) bot = TelegramBot(token)
        // Router needs a bot for Telegram features; if Telegram off, supply a dummy that won't be used.
        if (router == null) {
            val effectiveBot = bot ?: TelegramBot("__disabled__")
            router = BridgeRouter(this, app, effectiveBot)
        }

        when (action) {
            ACTION_INBOUND_SMS -> handleInboundSms(intent)
            ACTION_START -> if (telegramOn) ensurePolling()
            ACTION_STOP -> {
                pollJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun handleInboundSms(intent: Intent?) {
        val msgId = intent?.getLongExtra(EXTRA_MSG_ID, -1L) ?: -1L
        val address = intent?.getStringExtra(EXTRA_ADDRESS) ?: return
        val body = intent.getStringExtra(EXTRA_BODY) ?: return
        val ts = intent.getLongExtra(EXTRA_TS, System.currentTimeMillis())
        ensurePolling()
        scope.launch {
            runCatching { router?.onInboundSms(msgId, address, body, ts) }
                .onFailure { Logger.e("onInboundSms failed", it) }
        }
    }

    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        val r = router ?: return
        val b = bot ?: return
        val app = applicationContext as NomadApp

        pollJob = scope.launch {
            Logger.i("Telegram long-poll started")
            var offset = app.prefs.lastUpdateId + 1
            while (isActive) {
                try {
                    val updates = withContext(Dispatchers.IO) { b.getUpdates(offset) }
                    for (u in updates) {
                        offset = u.updateId + 1
                        app.prefs.lastUpdateId = u.updateId
                        val msg = u.message ?: u.editedMessage ?: continue
                        runCatching { r.onTelegramMessage(msg) }
                            .onFailure { Logger.e("onTelegramMessage failed", it) }
                    }
                } catch (t: Throwable) {
                    Logger.w("poll loop error: ${t.message}")
                    delay(5_000)
                }
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NomadApp.CHANNEL_BRIDGE)
            .setContentTitle("Nomad")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ai.nomad.action.START"
        const val ACTION_STOP = "ai.nomad.action.STOP"
        const val ACTION_INBOUND_SMS = "ai.nomad.action.INBOUND_SMS"
        private const val EXTRA_MSG_ID = "msg_id"
        private const val EXTRA_ADDRESS = "addr"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_TS = "ts"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, BridgeService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, BridgeService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        fun notifyInboundSms(
            context: Context,
            msgId: Long,
            address: String,
            body: String,
            timestamp: Long
        ) {
            val i = Intent(context, BridgeService::class.java)
                .setAction(ACTION_INBOUND_SMS)
                .putExtra(EXTRA_MSG_ID, msgId)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_BODY, body)
                .putExtra(EXTRA_TS, timestamp)
            context.startForegroundService(i)
        }
    }
}
