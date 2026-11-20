package com.novaagent.app.net

import android.content.Context
import com.novaagent.app.data.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** MCP streamable-HTTP client. Android build intentionally does not launch stdio servers. */
object McpClient {
    data class Server(val name: String, val endpoint: String, val token: String = "")
    data class Tool(val server: Server, val name: String, val description: String, val schema: JSONObject)
    private data class Session(val server: Server, val id: String)
    private val ids = AtomicInteger()
    private const val PREF = "mcpServers"

    fun servers(context: Context): List<Server> {
        val raw = context.getSharedPreferences("novaagent", 0).getString(PREF, "[]") ?: "[]"
        val a = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Server(it.optString("name"), it.optString("endpoint"), SecretStore.get(context, "mcp-$i")) }
        }.filter { allowed(it.endpoint) }
    }

    fun save(context: Context, servers: List<Server>) {
        val data = JSONArray().apply {
            servers.forEach { put(JSONObject().put("name", it.name).put("endpoint", it.endpoint)) }
        }
        context.getSharedPreferences("novaagent", 0).edit().putString(PREF, data.toString()).apply()
        servers.forEachIndexed { i, server -> SecretStore.put(context, "mcp-$i", server.token) }
    }

    fun listTools(context: Context): List<Tool> = servers(context).flatMap { server ->
        val session = initialize(server)
        val result = request(session, "tools/list", JSONObject())
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
            val result = request(initialize(server), "tools/call", JSONObject().put("name", toolName).put("arguments", args))
            val content = result.optJSONArray("content") ?: JSONArray()
            (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text") }
                .joinToString("\n").ifBlank { result.toString() }
        }.getOrElse { "MCP error: ${it.message ?: it.javaClass.simpleName}" }
    }

    fun definitions(context: Context): JSONArray = JSONArray().apply {
        val tools = listTools(context)
        val used = mutableSetOf<String>()
        tools.forEach { tool ->
            val name = uniqueName(tool, used)
            put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", name).put("description", "MCP ${tool.server.name}: ${tool.description}".take(500))
                .put("parameters", tool.schema)))
        }
    }

    fun invoke(context: Context, exportedName: String, args: JSONObject): String {
        val tools = runCatching { listTools(context) }.getOrElse { return "MCP discovery error: ${it.message}" }
        val used = mutableSetOf<String>()
        val tool = tools.firstOrNull { uniqueName(it, used) == exportedName }
            ?: return "MCP tool disappeared: $exportedName"
        return call(context, tool.server.name, tool.name, args)
    }

    private fun exportedName(tool: Tool) =
        "mcp_${tool.server.name}_${tool.name}".replace(Regex("[^A-Za-z0-9_-]"), "_").take(56)

    private fun uniqueName(tool: Tool, used: MutableSet<String>): String {
        val base = exportedName(tool)
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) candidate = "${base}_$suffix".also { suffix++ }
        return candidate
    }

    private fun initialize(server: Server): Session {
        val initialized = request(Session(server, ""), "initialize", JSONObject()
            .put("protocolVersion", "2025-03-26").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "NovaAgent").put("version", "1")))
        val id = initialized.optString("_sessionId")
        val session = Session(server, id)
        request(session, "notifications/initialized", JSONObject(), notification = true)
        return session
    }

    private fun allowed(endpoint: String): Boolean = runCatching {
        val u = URI(endpoint)
        when (u.scheme?.lowercase()) {
            "https" -> true
            "http" -> {
                val host = u.host?.lowercase() ?: return@runCatching false
                host == "localhost" || host == "127.0.0.1" ||
                    host.startsWith("10.") && host.substringAfter("10.").contains('.') ||
                    host.startsWith("192.168.") && host.substringAfter("192.168.").contains('.')
            }
            else -> false
        }
    }.getOrDefault(false)

    private fun request(session: Session, method: String, params: JSONObject, notification: Boolean = false): JSONObject {
        val payload = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        if (!notification) payload.put("id", ids.incrementAndGet())
        val conn = (URL(session.server.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 60_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (session.id.isNotBlank()) setRequestProperty("Mcp-Session-Id", session.id)
            if (session.server.token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${session.server.token}")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (notification && code in 200..299) return JSONObject()
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(300)}")
            val lines = text.replace("\r\n", "\n").lineSequence()
            val events = mutableListOf<String>()
            val event = StringBuilder()
            for (line in lines) {
                if (line.isBlank()) {
                    if (event.isNotEmpty()) { events += event.toString(); event.clear() }
                } else if (line.startsWith("data:")) {
                    if (event.isNotEmpty()) event.append('\n')
                    event.append(line.removePrefix("data:").trimStart())
                }
            }
            if (event.isNotEmpty()) events += event.toString()
            val response = events.asSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .firstOrNull { it.has("result") || it.has("error") } ?: run {
                    if (events.isEmpty()) JSONObject(text.trim()) else throw IOException("MCP SSE had no JSON-RPC result")
                }
            val newSession = conn.getHeaderField("Mcp-Session-Id")
            if (newSession != null) response.optJSONObject("result")?.put("_sessionId", newSession)
            response.optJSONObject("error")?.let { throw IOException(it.optString("message", it.toString())) }
            return response.optJSONObject("result") ?: JSONObject()
        } finally { conn.disconnect() }
    }
}
