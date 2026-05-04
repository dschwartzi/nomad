package ai.nomad.bridge

import ai.nomad.NomadApp
import ai.nomad.data.EventEntity
import ai.nomad.sms.SmsSender
import ai.nomad.telegram.TelegramBot
import ai.nomad.telegram.TgMessage
import ai.nomad.util.Logger
import android.content.Context

/**
 * All the "what to do" logic, separate from transport.
 * Takes raw events (SMS in, Telegram message in) and produces outbound effects.
 */
class BridgeRouter(
    private val context: Context,
    private val app: NomadApp,
    private val bot: TelegramBot
) {

    private val chatId: String get() = app.prefs.telegramChatId

    // --- Telegram side ---------------------------------------------------

    /**
     * Handle an incoming Telegram message. Returns true if handled (caller should advance offset).
     */
    suspend fun onTelegramMessage(msg: TgMessage) {
        // Only accept messages from the configured chat
        if (msg.chat.id.toString() != chatId) {
            Logger.w("Ignoring Telegram message from unauthorized chat ${msg.chat.id}")
            return
        }
        val text = msg.text?.trim().orEmpty()
        if (text.isEmpty()) return

        when {
            text.startsWith("/") -> handleCommand(msg, text)
            msg.replyTo != null -> handleReplyToBotMessage(msg)
            else -> handleBareText(msg, text)
        }
    }

    private suspend fun handleCommand(msg: TgMessage, text: String) {
        val cmd = text.substringBefore(' ').substringBefore('@').lowercase()
        val rest = text.substringAfter(' ', "").trim()

        when (cmd) {
            "/start", "/help" -> bot.sendMessage(chatId, HELP_TEXT)
            "/status" -> bot.sendMessage(chatId, statusMessage())
            "/send" -> handleSendCommand(rest)
            "/recent" -> handleRecentCommand()
            "/to" -> handleToCommand(rest)
            else -> bot.sendMessage(chatId, "Unknown command: <code>$cmd</code>\n\n$HELP_TEXT")
        }
    }

    /**
     * When user replies to a Nomad-forwarded SMS message in Telegram,
     * route their reply text back to the original sender.
     */
    private suspend fun handleReplyToBotMessage(msg: TgMessage) {
        val replyTo = msg.replyTo ?: return
        val text = msg.text?.trim().orEmpty()
        if (text.isEmpty()) return

        // Extract original address from our forwarded message format
        val address = extractAddressFromForwardedMessage(replyTo.text.orEmpty())
        if (address == null) {
            bot.sendMessage(
                chatId,
                "⚠️ Couldn't find a phone number in the message you're replying to. " +
                    "Use <code>/send &lt;number&gt; &lt;text&gt;</code> instead.",
                replyToMessageId = msg.messageId
            )
            return
        }
        sendSmsAndAck(address, text, msg.messageId)
    }

    /**
     * Bare text (no command, no reply) — send to the active thread if there is one,
     * otherwise prompt the user to use /send.
     */
    private suspend fun handleBareText(msg: TgMessage, text: String) {
        val active = app.prefs.activeThreadAddress
        if (active.isBlank()) {
            bot.sendMessage(
                chatId,
                "ℹ️ No active conversation. Reply to a forwarded SMS, " +
                    "or use <code>/send &lt;number&gt; &lt;text&gt;</code>, " +
                    "or <code>/to &lt;number&gt;</code> to set the active thread.",
                replyToMessageId = msg.messageId
            )
            return
        }
        sendSmsAndAck(active, text, msg.messageId)
    }

    private suspend fun handleSendCommand(rest: String) {
        if (rest.isBlank()) {
            bot.sendMessage(chatId, "Usage: <code>/send +15551234567 Hello there</code>")
            return
        }
        val target = rest.substringBefore(' ')
        val body = rest.substringAfter(' ', "").trim()
        if (body.isEmpty()) {
            bot.sendMessage(chatId, "Message body is empty.")
            return
        }
        val number = resolveAddressOrName(target)
        if (number == null) {
            bot.sendMessage(chatId, "Could not resolve <code>$target</code> to a phone number.")
            return
        }
        sendSmsAndAck(number, body, null)
    }

    private suspend fun handleToCommand(rest: String) {
        if (rest.isBlank()) {
            app.prefs.activeThreadAddress = ""
            bot.sendMessage(chatId, "Active thread cleared.")
            return
        }
        val number = resolveAddressOrName(rest)
        if (number == null) {
            bot.sendMessage(chatId, "Could not resolve <code>$rest</code> to a phone number.")
            return
        }
        app.prefs.activeThreadAddress = number
        val name = ContactResolver.nameFor(context, number) ?: number
        bot.sendMessage(chatId, "✏️ Active thread set: <b>$name</b> (<code>$number</code>). Just type to reply.")
    }

    private suspend fun handleRecentCommand() {
        val recent = app.db.messageDao().recentAllOnce(limit = 10)
        if (recent.isEmpty()) {
            bot.sendMessage(chatId, "No recent messages.")
            return
        }
        val sb = StringBuilder("<b>Recent messages</b>\n\n")
        for (m in recent) {
            val name = ContactResolver.nameFor(context, m.address) ?: m.address
            val arrow = if (m.direction == 0) "📩" else "📤"
            sb.append("$arrow <b>${escape(name)}</b>\n")
                .append(escape(m.body.take(120)))
                .append("\n\n")
        }
        bot.sendMessage(chatId, sb.toString())
    }

    // --- SMS side --------------------------------------------------------

    suspend fun onInboundSms(msgId: Long, address: String, body: String, timestamp: Long) {
        if (chatId.isBlank()) {
            Logger.w("Inbound SMS but Telegram chatId not configured; skipping forward")
            return
        }
        val name = ContactResolver.nameFor(context, address)
        val header = if (name != null) {
            "📩 <b>${escape(name)}</b> <code>${escape(address)}</code>"
        } else {
            "📩 <code>${escape(address)}</code>"
        }
        val out = "$header\n\n${escape(body)}"
        val tgMsgId = bot.sendMessage(chatId, out)
        if (tgMsgId != null) {
            app.db.messageDao().markForwarded(msgId, tgMsgId)
            app.prefs.activeThreadAddress = address
        }
        app.db.eventDao().insert(
            EventEntity(
                timestamp = System.currentTimeMillis(),
                level = "INFO",
                message = "Forwarded SMS from $address to Telegram (tgId=$tgMsgId)"
            )
        )
    }

    // --- Helpers ---------------------------------------------------------

    private suspend fun sendSmsAndAck(address: String, text: String, replyToTgId: Long?) {
        val result = SmsSender.send(context, address, text)
        if (result.isSuccess) {
            val name = ContactResolver.nameFor(context, address) ?: address
            bot.sendMessage(
                chatId,
                "✅ Sent to <b>${escape(name)}</b> <code>${escape(address)}</code>",
                replyToMessageId = replyToTgId
            )
            app.prefs.activeThreadAddress = address
        } else {
            val err = result.exceptionOrNull()?.message ?: "unknown"
            bot.sendMessage(
                chatId,
                "❌ Failed to send to <code>${escape(address)}</code>: ${escape(err)}",
                replyToMessageId = replyToTgId
            )
        }
    }

    private fun resolveAddressOrName(input: String): String? {
        val trimmed = input.trim().trim(':', ',', ';')
        if (trimmed.isEmpty()) return null
        // Looks phone-like: digits, optional leading +, spaces, dashes, parentheses
        if (trimmed.matches(Regex("""^[+]?[\d\s().\-]{5,}$"""))) {
            return trimmed.replace(Regex("""[\s().\-]"""), "")
        }
        return ContactResolver.numberFor(context, trimmed)
    }

    private fun extractAddressFromForwardedMessage(html: String): String? {
        // Our forwarded format includes <code>+1555...</code>
        val m = Regex("""<code>([^<]+)</code>""").find(html) ?: return null
        val candidate = m.groupValues[1].trim()
        return if (candidate.matches(Regex("""^[+]?[\d\s().\-]{5,}$"""))) {
            candidate.replace(Regex("""[\s().\-]"""), "")
        } else null
    }

    private fun statusMessage(): String {
        val active = app.prefs.activeThreadAddress
        val activeLine = if (active.isBlank()) "none" else {
            val name = ContactResolver.nameFor(context, active)
            if (name != null) "$name ($active)" else active
        }
        return "🟢 Nomad bridge is online.\nActive thread: <b>${escape(activeLine)}</b>"
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        const val HELP_TEXT = """<b>Nomad — commands</b>

<b>/send</b> &lt;number-or-name&gt; &lt;text&gt;
Send an SMS from your home phone.
Example: <code>/send +15551234567 Running late</code>
Example: <code>/send mom see you soon</code>

<b>/to</b> &lt;number-or-name&gt;
Set active thread, then just type messages to keep replying.

<b>/recent</b>
Show last 10 messages in/out.

<b>/status</b>
Show bridge status and active thread.

<b>Reply</b> to any forwarded SMS to respond to that sender.
<b>Type</b> any plain text to reply to the active thread."""
    }
}
