package com.novaagent.app.net

import com.novaagent.app.data.ProviderConfig
import com.novaagent.app.data.UiMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/**
 * OpenAI-compatible chat client. Non-streaming on purpose: SSE parsing buys nothing
 * before tool-calling exists (M2), and one JSON reply is far less code to get right.
 * ponytail: upgrade to stream=true when token latency becomes the bottleneck.
 */
object LlmClient {

    fun chat(config: ProviderConfig, messages: List<UiMessage>, system: String? = null): String {
        if (!config.isUsable) throw IOException("Provider belum diatur: isi API key dan model di Settings.")

        val payload = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                system?.takeIf { it.isNotBlank() }?.let {
                    put(JSONObject().put("role", "system").put("content", it))
                }
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
            })
        }

        val conn = (URL(config.chatEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
        }

        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: " + body.take(400))
            return parseContent(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseContent(body: String): String {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices")
            ?: throw IOException("Respons tanpa 'choices': " + body.take(200))
        if (choices.length() == 0) throw IOException("Respons tanpa pilihan.")
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IOException("Respons tanpa 'message'.")
        return message.optString("content").ifBlank {
            throw IOException("Balasan kosong dari provider.")
        }
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
