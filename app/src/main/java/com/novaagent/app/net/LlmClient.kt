package com.novaagent.app.net

import com.novaagent.app.data.ProviderConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject,
)

data class Reply(
    val content: String,
    val toolCalls: List<ToolCall>,
    /** Assistant message exactly as the provider sent it — echoed back verbatim to continue a tool round. */
    val raw: JSONObject,
)

/**
 * OpenAI-compatible chat client. Non-streaming on purpose: one JSON reply is far
 * less code than SSE parsing, and the difference only shows up on long answers.
 * ponytail: switch to stream=true when token latency becomes the bottleneck.
 */
object LlmClient {

    fun fetchModels(config: ProviderConfig): List<String> {
        val conn = (URL(config.modelsEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer " + asciiOnly(config.apiKey))
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: " + text.take(300))
            val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
            return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
                .filter { it.isNotBlank() }.distinct().sorted()
        } finally { conn.disconnect() }
    }

    fun chatWithTools(
        config: ProviderConfig,
        messages: List<JSONObject>,
        system: String?,
        tools: String?,
    ): Reply {
        if (!config.isUsable) throw IOException("Provider belum diatur: isi base URL dan model di Settings.")

        val primary = config.model
        var last: IOException? = null
        repeat(4) { attempt ->
            try {
                return request(config, primary, messages, system, tools)
            } catch (e: IOException) {
                last = e
                if (attempt < 3) Thread.sleep(500L * (attempt + 1))
            }
        }
        val fallback = config.fallbackModel.trim()
        if (fallback.isNotBlank() && fallback != primary) {
            try {
                return request(config.copy(model = fallback), fallback, messages, system, tools)
            } catch (e: IOException) {
                last = IOException("Model utama '$primary' gagal 4 kali; fallback '$fallback' juga gagal: ${e.message}", e)
            }
        }
        throw last ?: IOException("Model '$primary' gagal.")
    }

    private fun request(
        config: ProviderConfig,
        model: String,
        messages: List<JSONObject>,
        system: String?,
        tools: String?,
    ): Reply {
        if (!config.isUsable) throw IOException("Provider belum diatur: isi base URL dan model di Settings.")

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                system?.takeIf { it.isNotBlank() }?.let {
                    put(JSONObject().put("role", "system").put("content", it))
                }
                messages.forEach { put(it) }
            })
            tools?.let { put("tools", JSONArray(it)) }
        }

        val conn = (URL(config.chatEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
        }

        val body = try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: " + text.take(400))
            text
        } finally {
            conn.disconnect()
        }

        return parse(body)
    }

    /** For callers that only want prose (no tool loop). */
    fun chat(config: ProviderConfig, messages: List<JSONObject>, system: String? = null): String {
        val reply = chatWithTools(config, messages, system, tools = null)
        return reply.content.ifBlank { throw IOException("Balasan kosong dari provider.") }
    }

    /**
     * Header values must be printable ASCII. A pasted key with a zero-width space
     * or a smart quote would otherwise throw from deep inside the connection and
     * take the whole service process down.
     */
    private fun asciiOnly(value: String): String =
        value.filter { it in '!'..'~' }.trim()

    private fun parse(body: String): Reply {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices")
            ?: throw IOException("Respons tanpa 'choices': " + body.take(200))
        if (choices.length() == 0) throw IOException("Respons tanpa pilihan.")
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IOException("Respons tanpa 'message'.")

        val calls = message.optJSONArray("tool_calls")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val fn = c.optJSONObject("function") ?: return@mapNotNull null
                ToolCall(
                    id = c.optString("id"),
                    name = fn.optString("name"),
                    arguments = parseArgs(fn.optString("arguments")),
                )
            }
        }.orEmpty()

        return Reply(content = message.optString("content"), toolCalls = calls, raw = message)
    }

    /** Models sometimes emit invalid JSON for arguments; degrade to an empty object rather than crash. */
    private fun parseArgs(raw: String): JSONObject = try {
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (e: Exception) {
        JSONObject()
    }

    /** Cheap reachability probe for the Settings screen; returns null when the host answers. */
    fun probe(config: ProviderConfig): String? = try {
        val conn = URL(config.chatEndpoint).openConnection() as URLConnection
        conn.connectTimeout = 8_000
        conn.getInputStream().close()
        null
    } catch (e: IOException) {
        e.message ?: e.javaClass.simpleName
    } catch (e: IllegalArgumentException) {
        "URL tidak valid: " + config.chatEndpoint
    }
}
