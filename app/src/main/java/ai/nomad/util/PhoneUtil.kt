package ai.nomad.util

import android.telephony.PhoneNumberUtils

object PhoneUtil {
    /** Strip formatting to a bare dialable string (keeps leading +). */
    fun normalize(s: String): String = s.trim().replace(Regex("""[\s().\-]"""), "")

    /**
     * Loose equality: uses Android's PhoneNumberUtils.compare which tolerates
     * country-code and national-format differences ("+15551234567" ~ "5551234567").
     */
    fun sameNumber(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return PhoneNumberUtils.compare(normalize(a), normalize(b))
    }

    /** True if [address] matches any number in [allowlist] (loose comparison). */
    fun isOneOf(address: String, allowlist: List<String>): Boolean =
        allowlist.any { sameNumber(it, address) }
}
