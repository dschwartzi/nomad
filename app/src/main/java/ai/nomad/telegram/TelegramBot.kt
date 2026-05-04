package ai.nomad.telegram

import ai.nomad.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Thin Telegram Bot API client using long polling.
 * All methods are blocking — call from a coroutine dispatched to IO.
 */
class TelegramBot(private val token: String) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS) // longer than long-poll timeout
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun url(method: String) = "https://api.telegram.org/bot$token/$method"

    /** Long-poll for updates. Blocks up to [timeoutSec] seconds waiting for activity. */
    fun getUpdates(offset: Long, timeoutSec: Int = 50): List<TgUpdate> {
        val body = buildJsonObject {
            put("offset", offset)
            put("timeout", timeoutSec)
            put("allowed_updates", buildJsonArray { add(JsonPrimitive("message")) })
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url(url("getUpdates")).post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: return emptyList()
            if (!resp.isSuccessful) {
                Logger.w("getUpdates http=${resp.code} body=$text")
                return emptyList()
            }
            val parsed = json.decodeFromString(
                TgResponse.serializer(kotlinx.serialization.builtins.ListSerializer(TgUpdate.serializer())),
                text
            )
            if (!parsed.ok) {
                Logger.w("getUpdates !ok: ${parsed.description}")
                return emptyList()
            }
            return parsed.result ?: emptyList()
        }
    }

    /** Send a text message. Returns the sent message id on success, or null on failure. */
    fun sendMessage(
        chatId: String,
        text: String,
        replyToMessageId: Long? = null,
        parseMode: String? = "HTML"
    ): Long? {
        val body = buildJsonObject {
            put("chat_id", chatId)
            put("text", text)
            if (parseMode != null) put("parse_mode", parseMode)
            if (replyToMessageId != null) put("reply_to_message_id", replyToMessageId)
            put("disable_web_page_preview", true)
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url(url("sendMessage")).post(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text2 = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    Logger.w("sendMessage http=${resp.code} body=$text2")
                    return null
                }
                val parsed = json.decodeFromString(
                    TgResponse.serializer(TgSentMessage.serializer()),
                    text2
                )
                parsed.result?.messageId
            }
        } catch (t: Throwable) {
            Logger.w("sendMessage threw: ${t.message}")
            null
        }
    }

    /** Test call — returns bot username on success. */
    fun getMe(): String? {
        val req = Request.Builder().url(url("getMe")).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return null
                if (!resp.isSuccessful) return null
                val root = json.parseToJsonElement(text) as? JsonObject ?: return null
                val result = root["result"] as? JsonObject ?: return null
                (result["username"]?.toString() ?: "").trim('"')
            }
        } catch (t: Throwable) {
            Logger.w("getMe threw: ${t.message}")
            null
        }
    }
}
