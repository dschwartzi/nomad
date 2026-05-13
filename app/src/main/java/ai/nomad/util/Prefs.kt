package ai.nomad.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Prefs(context: Context) {

    private val sp: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "nomad_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // fallback if keystore misbehaves on some devices
        context.getSharedPreferences("nomad_prefs", Context.MODE_PRIVATE)
    }

    var telegramBotToken: String
        get() = sp.getString(KEY_TG_TOKEN, "") ?: ""
        set(value) { sp.edit().putString(KEY_TG_TOKEN, value).apply() }

    var telegramChatId: String
        get() = sp.getString(KEY_TG_CHAT, "") ?: ""
        set(value) { sp.edit().putString(KEY_TG_CHAT, value).apply() }

    var bridgeEnabled: Boolean
        get() = sp.getBoolean(KEY_BRIDGE_ON, false)
        set(value) { sp.edit().putBoolean(KEY_BRIDGE_ON, value).apply() }

    var lastUpdateId: Long
        get() = sp.getLong(KEY_LAST_UPDATE_ID, 0L)
        set(value) { sp.edit().putLong(KEY_LAST_UPDATE_ID, value).apply() }

    /** The thread the user most recently replied to (for bare-text replies from Telegram). */
    var activeThreadAddress: String
        get() = sp.getString(KEY_ACTIVE_THREAD, "") ?: ""
        set(value) { sp.edit().putString(KEY_ACTIVE_THREAD, value).apply() }

    // --- SMS transport settings ---

    /** Comma-separated list of travel numbers allowed to SMS-command this phone. */
    var trustedTravelNumbersRaw: String
        get() = sp.getString(KEY_TRUSTED_NUMS, "") ?: ""
        set(value) { sp.edit().putString(KEY_TRUSTED_NUMS, value).apply() }

    val trustedTravelNumbers: List<String>
        get() = trustedTravelNumbersRaw
            .split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    var smsCommandPrefix: String
        get() = sp.getString(KEY_SMS_PREFIX, "#") ?: "#"
        set(value) { sp.edit().putString(KEY_SMS_PREFIX, value).apply() }

    var smsFallbackEnabled: Boolean
        get() = sp.getBoolean(KEY_SMS_FALLBACK, true)
        set(value) { sp.edit().putBoolean(KEY_SMS_FALLBACK, value).apply() }

    /** Destination phone number for SMS fallback. Set explicitly in Settings, or
     *  bootstrapped from the first #command sender. Once non-empty, command
     *  handling will NOT overwrite it. */
    var smsFallbackDestination: String
        get() = sp.getString(KEY_SMS_FALLBACK_DEST, "") ?: ""
        set(value) { sp.edit().putString(KEY_SMS_FALLBACK_DEST, value).apply() }

    /** If the Travel app hasn't been seen in this many minutes, treat it as
     *  offline and route inbound SMS via the SMS fallback (in addition to the
     *  relay, which is always attempted). */
    var smsFallbackOfflineMinutes: Int
        get() = sp.getInt(KEY_SMS_FALLBACK_OFFLINE_MIN, 10)
        set(value) { sp.edit().putInt(KEY_SMS_FALLBACK_OFFLINE_MIN, value.coerceAtLeast(1)).apply() }

    /** If true, send the SMS fallback for *every* inbound SMS, regardless of
     *  whether the Travel app appears online. The relay path still runs in
     *  parallel. */
    var smsFallbackAlways: Boolean
        get() = sp.getBoolean(KEY_SMS_FALLBACK_ALWAYS, false)
        set(value) { sp.edit().putBoolean(KEY_SMS_FALLBACK_ALWAYS, value).apply() }

    /** Wall-clock millis of the most recent payload we received from the Travel
     *  app over the relay (any message type — PING, SMS_OUT, etc). 0 = never. */
    var lastSeenTravelAt: Long
        get() = sp.getLong(KEY_LAST_SEEN_TRAVEL_AT, 0L)
        set(value) { sp.edit().putLong(KEY_LAST_SEEN_TRAVEL_AT, value).apply() }

    /** True if we believe the Travel app is currently reachable (i.e. it has
     *  contacted us within the configured offline threshold). */
    fun isTravelOnline(now: Long = System.currentTimeMillis()): Boolean {
        val last = lastSeenTravelAt
        if (last == 0L) return false
        val thresholdMs = smsFallbackOfflineMinutes.toLong() * 60_000L
        return (now - last) <= thresholdMs
    }

    // --- Relay (FCM) settings ---

    /** Pairing ID assigned by relay server once paired with the other phone. */
    var relayPairingId: String
        get() = sp.getString(KEY_RELAY_PAIRING_ID, "") ?: ""
        set(value) { sp.edit().putString(KEY_RELAY_PAIRING_ID, value).apply() }

    /** Shared secret for authenticating with relay server. */
    var relaySecret: String
        get() = sp.getString(KEY_RELAY_SECRET, "") ?: ""
        set(value) { sp.edit().putString(KEY_RELAY_SECRET, value).apply() }

    /** "home" on H phone, "travel" on T phone. */
    var relayRole: String
        get() = sp.getString(KEY_RELAY_ROLE, "home") ?: "home"
        set(value) { sp.edit().putString(KEY_RELAY_ROLE, value).apply() }

    /** Last known FCM token for this device. */
    var fcmToken: String
        get() = sp.getString(KEY_FCM_TOKEN, "") ?: ""
        set(value) { sp.edit().putString(KEY_FCM_TOKEN, value).apply() }

    /** Relay base URL (entered by user — never bundled in source). */
    var relayBaseUrl: String
        get() = sp.getString(KEY_RELAY_BASE_URL, "") ?: ""
        set(value) { sp.edit().putString(KEY_RELAY_BASE_URL, value).apply() }

    /** Shared account key for the relay (X-Account-Key header). Entered by user. */
    var relayAccountKey: String
        get() = sp.getString(KEY_RELAY_ACCOUNT_KEY, "") ?: ""
        set(value) { sp.edit().putString(KEY_RELAY_ACCOUNT_KEY, value).apply() }

    /** Both URL + account key must be set before we can talk to the relay at all. */
    fun hasRelayCredentials(): Boolean =
        relayBaseUrl.isNotBlank() && relayAccountKey.isNotBlank()

    fun isRelayPaired(): Boolean =
        hasRelayCredentials() && relayPairingId.isNotBlank() && relaySecret.isNotBlank()

    fun clearRelayPairing() {
        sp.edit()
            .remove(KEY_RELAY_PAIRING_ID)
            .remove(KEY_RELAY_SECRET)
            .apply()
    }

    fun isConfigured(): Boolean =
        (telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()) || isRelayPaired()

    companion object {
        private const val KEY_TG_TOKEN = "tg_token"
        private const val KEY_TG_CHAT = "tg_chat_id"
        private const val KEY_BRIDGE_ON = "bridge_enabled"
        private const val KEY_LAST_UPDATE_ID = "last_update_id"
        private const val KEY_ACTIVE_THREAD = "active_thread"
        private const val KEY_TRUSTED_NUMS = "trusted_travel_numbers"
        private const val KEY_SMS_PREFIX = "sms_command_prefix"
        private const val KEY_SMS_FALLBACK = "sms_fallback_enabled"
        private const val KEY_SMS_FALLBACK_DEST = "sms_fallback_dest"
        private const val KEY_SMS_FALLBACK_OFFLINE_MIN = "sms_fallback_offline_min"
        private const val KEY_SMS_FALLBACK_ALWAYS = "sms_fallback_always"
        private const val KEY_LAST_SEEN_TRAVEL_AT = "last_seen_travel_at"
        private const val KEY_RELAY_PAIRING_ID = "relay_pairing_id"
        private const val KEY_RELAY_SECRET = "relay_secret"
        private const val KEY_RELAY_ROLE = "relay_role"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_RELAY_BASE_URL = "relay_base_url"
        private const val KEY_RELAY_ACCOUNT_KEY = "relay_account_key"
    }
}
