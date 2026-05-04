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

    fun isConfigured(): Boolean =
        telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()

    companion object {
        private const val KEY_TG_TOKEN = "tg_token"
        private const val KEY_TG_CHAT = "tg_chat_id"
        private const val KEY_BRIDGE_ON = "bridge_enabled"
        private const val KEY_LAST_UPDATE_ID = "last_update_id"
        private const val KEY_ACTIVE_THREAD = "active_thread"
    }
}
