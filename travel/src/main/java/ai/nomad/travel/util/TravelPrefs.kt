package ai.nomad.travel.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TravelPrefs(context: Context) {

    private val sp: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "nomad_travel_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("nomad_travel_plain", Context.MODE_PRIVATE)
    }

    var pairingId: String
        get() = sp.getString(KEY_PAIRING_ID, "") ?: ""
        set(v) { sp.edit().putString(KEY_PAIRING_ID, v).apply() }

    var secret: String
        get() = sp.getString(KEY_SECRET, "") ?: ""
        set(v) { sp.edit().putString(KEY_SECRET, v).apply() }

    var fcmToken: String
        get() = sp.getString(KEY_FCM, "") ?: ""
        set(v) { sp.edit().putString(KEY_FCM, v).apply() }

    var relayBaseUrl: String
        get() = sp.getString(KEY_BASE_URL, "") ?: ""
        set(v) { sp.edit().putString(KEY_BASE_URL, v).apply() }

    var accountKey: String
        get() = sp.getString(KEY_ACCOUNT_KEY, "") ?: ""
        set(v) { sp.edit().putString(KEY_ACCOUNT_KEY, v).apply() }

    fun hasRelayCredentials(): Boolean =
        relayBaseUrl.isNotBlank() && accountKey.isNotBlank()

    fun isPaired(): Boolean =
        hasRelayCredentials() && pairingId.isNotBlank() && secret.isNotBlank()

    fun clearPairing() {
        sp.edit().remove(KEY_PAIRING_ID).remove(KEY_SECRET).apply()
    }

    companion object {
        private const val KEY_PAIRING_ID = "pairing_id"
        private const val KEY_SECRET = "secret"
        private const val KEY_FCM = "fcm_token"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ACCOUNT_KEY = "account_key"
    }
}
