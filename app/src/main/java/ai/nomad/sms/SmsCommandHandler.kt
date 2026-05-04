package ai.nomad.sms

import ai.nomad.NomadApp
import ai.nomad.bridge.ContactResolver
import ai.nomad.data.EventEntity
import ai.nomad.util.Logger
import ai.nomad.util.PhoneUtil
import android.content.Context

/**
 * Handles #commands that arrive via SMS from a trusted travel number.
 * All responses go back out over SMS to the same travel number.
 *
 * Supported commands (prefix is configurable, default '#'):
 *   #send <number-or-name> <text>
 *   #reply <text>                — reply to active thread
 *   #to <number-or-name>         — set active thread
 *   #last [N]                    — show last N messages (default 5)
 *   #status
 *   #help
 */
object SmsCommandHandler {

    /**
     * Try to handle the given inbound SMS as a command.
     * @return true if it was a command (and was consumed); false if it's a normal SMS.
     */
    suspend fun tryHandle(
        context: Context,
        sender: String,
        body: String
    ): Boolean {
        val app = context.applicationContext as NomadApp
        val prefs = app.prefs

        val trusted = prefs.trustedTravelNumbers
        if (trusted.isEmpty()) return false
        if (!PhoneUtil.isOneOf(sender, trusted)) return false

        val prefix = prefs.smsCommandPrefix.ifBlank { "#" }
        if (!body.trimStart().startsWith(prefix)) return false

        // Remember this travel number as the fallback destination.
        prefs.smsFallbackDestination = sender

        val text = body.trimStart().removePrefix(prefix).trim()
        val cmd = text.substringBefore(' ').lowercase()
        val rest = text.substringAfter(' ', "").trim()

        Logger.i("SMS command from $sender: $cmd $rest")

        when (cmd) {
            "", "help" -> ack(context, sender, helpText(prefix))
            "status" -> ack(context, sender, statusText(context))
            "send" -> handleSend(context, sender, rest)
            "reply" -> handleReply(context, sender, rest)
            "to" -> handleTo(context, sender, rest)
            "last" -> handleLast(context, sender, rest)
            else -> ack(context, sender, "Unknown: $prefix$cmd\n\n${helpText(prefix)}")
        }

        app.db.eventDao().insert(
            EventEntity(
                timestamp = System.currentTimeMillis(),
                level = "INFO",
                message = "SMS command from $sender: $cmd"
            )
        )
        return true
    }

    private suspend fun handleSend(context: Context, sender: String, rest: String) {
        if (rest.isBlank()) { ack(context, sender, "Usage: #send +1555... body"); return }
        val target = rest.substringBefore(' ')
        val body = rest.substringAfter(' ', "").trim()
        if (body.isEmpty()) { ack(context, sender, "Empty body."); return }
        val number = resolve(context, target)
        if (number == null) { ack(context, sender, "Couldn't resolve '$target'"); return }
        sendAndAck(context, sender, number, body)
    }

    private suspend fun handleReply(context: Context, sender: String, rest: String) {
        if (rest.isBlank()) { ack(context, sender, "Usage: #reply your message"); return }
        val app = context.applicationContext as NomadApp
        val active = app.prefs.activeThreadAddress
        if (active.isBlank()) { ack(context, sender, "No active thread. Use #to first."); return }
        sendAndAck(context, sender, active, rest)
    }

    private suspend fun handleTo(context: Context, sender: String, rest: String) {
        val app = context.applicationContext as NomadApp
        if (rest.isBlank()) {
            app.prefs.activeThreadAddress = ""
            ack(context, sender, "Active thread cleared.")
            return
        }
        val number = resolve(context, rest)
        if (number == null) { ack(context, sender, "Couldn't resolve '$rest'"); return }
        app.prefs.activeThreadAddress = number
        val name = ContactResolver.nameFor(context, number) ?: number
        ack(context, sender, "Active: $name ($number)")
    }

    private suspend fun handleLast(context: Context, sender: String, rest: String) {
        val app = context.applicationContext as NomadApp
        val n = rest.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val recent = app.db.messageDao().recentAllOnce(limit = n)
        if (recent.isEmpty()) { ack(context, sender, "No messages."); return }
        val sb = StringBuilder()
        for (m in recent) {
            val name = ContactResolver.nameFor(context, m.address) ?: m.address
            val arrow = if (m.direction == 0) "<" else ">"
            sb.append("$arrow $name: ${m.body.take(80)}\n")
        }
        ack(context, sender, sb.toString().trim())
    }

    private suspend fun sendAndAck(context: Context, travelSender: String, to: String, body: String) {
        val r = SmsSender.send(context, to, body)
        if (r.isSuccess) {
            val app = context.applicationContext as NomadApp
            app.prefs.activeThreadAddress = to
            val name = ContactResolver.nameFor(context, to) ?: to
            ack(context, travelSender, "Sent to $name ($to)")
        } else {
            ack(context, travelSender, "Send failed: ${r.exceptionOrNull()?.message ?: "unknown"}")
        }
    }

    private fun resolve(context: Context, s: String): String? {
        val t = s.trim().trim(':', ',', ';')
        if (t.isEmpty()) return null
        if (t.matches(Regex("""^[+]?[\d\s().\-]{5,}$"""))) {
            return PhoneUtil.normalize(t)
        }
        return ContactResolver.numberFor(context, t)
    }

    private suspend fun ack(context: Context, to: String, text: String) {
        SmsSender.send(context, to, text)
    }

    private fun helpText(prefix: String): String =
        """Nomad commands:
${prefix}send +1555... body
${prefix}reply body
${prefix}to +1555... (or name)
${prefix}last [N]
${prefix}status
${prefix}help"""

    private fun statusText(context: Context): String {
        val app = context.applicationContext as NomadApp
        val active = app.prefs.activeThreadAddress.ifBlank { "none" }
        return "Nomad online. Active: $active"
    }
}
