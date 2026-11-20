package com.novaagent.app.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** MCP streamable-HTTP client. Android build intentionally does not launch stdio servers. */
object McpClient {
    data class Server(val name: String, val endpoint: String, val token: String = "")
    data class Tool(val server: Server, val name: String, val description: String, val schema: JSONObject)
    private val ids = AtomicInteger()
    private const val PREF = "mcpServers"

    fun servers(context: Context): List<Server> {
        val raw = context.getSharedPreferences("novaagent", 0).getString(PREF, "[]") ?: "[]"
        val a = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Server(it.optString("name"), it.optString("endpoint"), it.optString("token")) }
        }.filter { it.endpoint.startsWith("https://") || it.endpoint.startsWith("http://10.") ||
            it.endpoint.startsWith("http://192.168.") || it.endpoint.startsWith("http://127.0.0.1") ||
            it.endpoint.startsWith("http://localhost") }
    }

    fun save(context: Context, servers: List<Server>) {
        val data = JSONArray().apply {
            servers.forEach { put(JSONObject().put("name", it.name).put("endpoint", it.endpoint).put("token", it.token)) }
        }
        context.getSharedPreferences("novaagent", 0).edit().putString(PREF, data.toString()).apply()
    }

    fun listTools(context: Context): List<Tool> = servers(context).flatMap { server ->
        request(server, "initialize", JSONObject().put("protocolVersion", "2025-03-26")
            .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "NovaAgent").put("version", "1")))
        request(server, "notifications/initialized", JSONObject(), notification = true)
        val result = request(server, "tools/list", JSONObject())
        val arr = result.optJSONArray("tools") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Tool(server, it.optString("name"), it.optString("description"),
                it.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")) }
        }
    }

    fun call(context: Context, serverName: String, toolName: String, args: JSONObject): String {
        val server = servers(context).firstOrNull { it.name == serverName }
            ?: return "MCP server not configured: $serverName"
        return runCatching {
            request(server, "initialize", JSONObject().put("protocolVersion", "2025-03-26")
                .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "NovaAgent").put("version", "1")))
            request(server, "notifications/initialized", JSONObject(), notification = true)
            val result = request(server, "tools/call", JSONObject().put("name", toolName).put("arguments", args))
            val content = result.optJSONArray("content") ?: JSONArray()
            (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text") }
                .joinToString("\n").ifBlank { result.toString() }
        }.getOrElse { "MCP error: ${it.message ?: it.javaClass.simpleName}" }
    }

    fun definitions(context: Context): JSONArray = JSONArray().apply {
        listTools(context).forEach { tool ->
            val name = exportedName(tool)
            put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", name)
                .put("description", "MCP ${tool.server.name}: ${tool.description}".take(500))
                .put("parameters", tool.schema)))
        }
    }

    fun invoke(context: Context, exportedName: String, args: JSONObject): String {
        val tool = runCatching { listTools(context) }.getOrElse { return "MCP discovery error: ${it.message}" }
            .firstOrNull { exportedName(it) == exportedName }
            ?: return "MCP tool disappeared: $exportedName"
        return call(context, tool.server.name, tool.name, args)
    }

    private fun exportedName(tool: Tool) =
        "mcp_${tool.server.name}_${tool.name}".replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)

    private fun request(server: Server, method: String, params: JSONObject, notification: Boolean = false): JSONObject {
        val payload = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        if (!notification) payload.put("id", ids.incrementAndGet())
        val conn = (URL(server.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 60_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (server.token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${server.token}")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (notification && code in 200..299) return JSONObject()
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(300)}")
            val jsonText = if (text.startsWith("data:")) text.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim().orEmpty() else text
            val response = JSONObject(jsonText)
            response.optJSONObject("error")?.let { throw IOException(it.optString("message", it.toString())) }
            return response.optJSONObject("result") ?: JSONObject()
        } finally { conn.disconnect() }
    }
}
