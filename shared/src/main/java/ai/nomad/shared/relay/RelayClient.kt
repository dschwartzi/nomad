package ai.nomad.shared.relay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to the Nomad relay (Cloud Functions on Firebase) over HTTPS.
 * All endpoints accept POST with JSON and require the X-Account-Key header.
 *
 * Both [baseUrl] and [accountKey] must be supplied by the caller — there are no
 * defaults. The URL and key are never baked into the source; they are entered
 * manually in each app's settings and stored in encrypted prefs.
 */
class RelayClient(
    private val baseUrl: String,
    private val accountKey: String
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl required" }
        require(accountKey.isNotBlank()) { "accountKey required" }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    data class StartResult(
        val pairingId: String,
        val code: String,
        val secret: String,
        val expiresAt: Long
    )

    data class FinishResult(
        val pairingId: String,
        val secret: String,
        val partnerToken: String
    )

    /** Begin pairing — caller becomes the "pair starter". Returns a 6-digit code to display. */
    suspend fun pairStart(token: String, role: String): Result<StartResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("token", token)
                put("role", role)
            }
            val obj = postJson("pairStart", body)
            StartResult(
                pairingId = obj.str("pairingId"),
                code = obj.str("code"),
                secret = obj.str("secret"),
                expiresAt = obj.num("expiresAt")
            )
        }
    }

    /** Complete pairing on the other side using the displayed code. */
    suspend fun pairFinish(code: String, token: String, role: String): Result<FinishResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("code", code)
                    put("token", token)
                    put("role", role)
                }
                val obj = postJson("pairFinish", body)
                FinishResult(
                    pairingId = obj.str("pairingId"),
                    secret = obj.str("secret"),
                    partnerToken = obj.str("partnerToken")
                )
            }
        }

    /** Poll for partner token after a /pairStart. Returns partnerToken or null if not yet paired. */
    suspend fun pairStatus(pairingId: String, secret: String, role: String): Result<String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("pairingId", pairingId)
                    put("secret", secret)
                    put("role", role)
                }
                val obj = postJson("pairStatus", body)
                obj["partnerToken"]?.let {
                    if (it is JsonPrimitive && it.isString) it.content else null
                }
            }
        }

    /** Update our FCM token after rotation. */
    suspend fun updateToken(pairingId: String, secret: String, role: String, token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("pairingId", pairingId)
                    put("secret", secret)
                    put("role", role)
                    put("token", token)
                }
                postJson("updateToken", body)
                Unit
            }
        }

    /** Send a payload to the partner via the relay. Server forwards via FCM. */
    suspend fun send(
        pairingId: String,
        secret: String,
        role: String,
        payload: RelayMessage
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payloadJson = json.encodeToString(payload)
            val body = buildJsonObject {
                put("pairingId", pairingId)
                put("secret", secret)
                put("role", role)
                put("payload", json.parseToJsonElement(payloadJson))
            }
            postJson("send", body)
            Unit
        }
    }

    suspend fun health(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/health").get().build()
            http.newCall(req).execute().use { it.isSuccessful }
        }
    }

    private fun postJson(path: String, body: JsonObject): JsonObject {
        val req = Request.Builder()
            .url("$baseUrl/$path")
            .header("X-Account-Key", accountKey)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w("Nomad/Relay", "$path failed: ${resp.code} $text")
                throw RelayException(resp.code, text)
            }
            return json.parseToJsonElement(text) as JsonObject
        }
    }

    class RelayException(val status: Int, message: String) : RuntimeException("$status: $message")

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("missing $key")

    private fun JsonObject.num(key: String): Long =
        (this[key] as? JsonPrimitive)?.content?.toLong() ?: error("missing $key")

    companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
