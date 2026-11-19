package com.novaagent.app.agent

import android.content.Context
import com.novaagent.app.bridge.TermuxBridge
import com.novaagent.app.data.Limits
import com.novaagent.app.net.LlmClient
import com.novaagent.app.net.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tools the model can call. Every one of them executes inside Termux so shell,
 * file IO and llm share one namespace — the model's mental model of "the
 * machine" stays true, and no path needs translating between app sandbox and
 * Termux home.
 */
object AgentTools {

    /** Paths the app may touch, as visible from Termux. Everything else is refused. */
    private val allowedRoots = listOf(
        "/sdcard",
        "/storage/emulated/0",
        "/data/data/com.termux/files/home",
        "/data/data/com.termux/files/usr/tmp",
    )

    val defs: String = JSONArray().apply {
        put(tool(
            "read_file",
            "Baca file teks. Path boleh absolut (di /sdcard atau HOME Termux) " +
                "atau relatif ke working directory sesi.",
            JSONObject().apply {
                prop("path", "string", "Path file")
                prop("offset", "integer", "Baris awal (opsional, 1-based)")
                prop("limit", "integer", "Jumlah baris maksimum (opsional)")
            },
            listOf("path"),
        ))
        put(tool(
            "write_file",
            "Tulis (timpa) file teks. Membuat parent directory kalau belum ada.",
            JSONObject().apply {
                prop("path", "string", "Path file")
                prop("content", "string", "Isi lengkap file")
            },
            listOf("path", "content"),
        ))
        put(tool(
            "list_dir",
            "List isi directory, satu entri per baris.",
            JSONObject().apply { prop("path", "string", "Path directory") },
            listOf("path"),
        ))
        put(tool(
            "run_shell",
            "Jalankan perintah shell di Termux. Output digabung stdout+stderr.",
            JSONObject().apply {
                prop("command", "string", "Perintah bash")
                prop("timeout_ms", "integer", "Batas waktu ms (opsional, default 120000)")
            },
            listOf("command"),
        ))
    }.toString()

    private fun tool(name: String, description: String, props: JSONObject, required: List<String>) =
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", props)
                    put("required", JSONArray(required))
                })
            })
        }

    private fun JSONObject.prop(name: String, type: String, description: String) {
        put(name, JSONObject().apply { put("type", type); put("description", description) })
    }

    /** Returns the tool result as text; never throws. */
    fun execute(context: Context, name: String, args: JSONObject, workdir: String): String = try {
        when (name) {
            "read_file" -> readFile(args.optString("path"), args, workdir)
            "write_file" -> writeFile(args.optString("path"), args.optString("content"), workdir)
            "list_dir" -> listDir(args.optString("path"), workdir)
            "run_shell" -> runShell(context, args.optString("command"), args.optInt("timeout_ms", 120_000))
            else -> "Error: tool tidak dikenal '$name'. Tersedia: read_file, write_file, list_dir, run_shell."
        }
    } catch (e: Exception) {
        "Error: " + (e.message ?: e.javaClass.simpleName)
    }

    private fun readFile(path: String, args: JSONObject, workdir: String): String {
        val f = resolve(path, workdir)
        if (!f.isFile) return "Error: file tidak ditemukan: ${f.path}"
        val lines = f.readLines()
        val offset = (args.optInt("offset", 1)).coerceAtLeast(1)
        val limit = if (args.has("limit")) args.optInt("limit") else lines.size
        if (offset > lines.size) return "Error: offset $offset melebihi ${lines.size} baris."
        val slice = lines.drop(offset - 1).take(limit)
        val body = slice.withIndex().joinToString("\n") { "${it.index + offset}|${it.value}" }
        return buildString {
            appendLine("${f.path} — ${lines.size} baris, menampilkan $offset..${offset + slice.size - 1}")
            if (body.length > MAX_OUTPUT) append(body.take(MAX_OUTPUT) + "\n...[dipotong]") else append(body)
        }
    }

    private fun writeFile(path: String, content: String, workdir: String): String {
        val f = resolve(path, workdir)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "OK: ${f.path} ditulis (${content.length} karakter)."
    }

    private fun listDir(path: String, workdir: String): String {
        val f = resolve(path, workdir)
        if (!f.isDirectory) return "Error: bukan directory: ${f.path}"
        val entries = f.listFiles()?.sortedBy { it.name }?.joinToString("\n") {
            (if (it.isDirectory) "d " else "f ") + it.name
        }.orEmpty()
        return if (entries.isBlank()) "(kosong) ${f.path}" else entries
    }

    private fun runShell(context: Context, command: String, timeoutMs: Int): String {
        val capMb = Limits.memoryCapMb(context)
        // ulimit -v takes KB. Without a cap, one runaway command can get the whole
        // app OOM-killed by the system; with it, the command dies instead.
        val wrapped = if (capMb > 0) "ulimit -v ${capMb * 1024} 2>/dev/null; $command" else command
        val r = TermuxBridge.run(context, wrapped, timeoutMs.coerceIn(1_000, 600_000))
        return when {
            r.error != null -> "Error: ${r.error}"
            r.exitCode == 0 -> r.output.ifBlank { "(selesai, tanpa output)" }
            r.exitCode == 137 || r.output.contains("Cannot allocate memory") ->
                "Error: perintah dihentikan karena melewati batas memori $capMb MB " +
                    "(naikkan di Settings kalau memang butuh).\n${r.output}".trim()
            else -> "exit ${r.exitCode}\n${r.output}".take(MAX_OUTPUT + 40)
        }
    }

    private fun resolve(path: String, workdir: String): File {
        val raw = path.trim().ifBlank { workdir }
        val expanded = when {
            raw == "~" -> TERMUX_HOME
            raw.startsWith("~/") -> TERMUX_HOME + raw.removePrefix("~")
            raw.startsWith("/") -> raw
            workdir.isNotBlank() -> "$workdir/$raw"
            else -> raw
        }
        val f = File(expanded).canonicalFile
        if (allowedRoots.none { f.path == it || f.path.startsWith("$it/") }) {
            throw SecurityException("Path di luar area yang diizinkan: ${f.path}")
        }
        return f
    }

    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val MAX_OUTPUT = 8_000
}

/** Drives one assistant turn: call the model, run requested tools, repeat until it answers. */
object AgentLoop {

    private const val MAX_ROUNDS = 6

    fun run(
        context: Context,
        history: List<JSONObject>,
        system: String,
        workdir: String,
        onTool: (String, String) -> Unit,
    ): String {
        val messages = ArrayList(history)
        repeat(MAX_ROUNDS) { round ->
            val response = LlmClient.chatWithTools(
                com.novaagent.app.data.Settings.config.value, messages, system, AgentTools.defs,
            )

            val text = response.content.trim()
            if (response.toolCalls.isEmpty()) {
                return text.ifBlank { "(provider mengembalikan balasan kosong)" }
            }

            messages.add(response.raw)
            for (call in response.toolCalls) {
                onTool(call.name, summarize(call.arguments))
                val result = AgentTools.execute(context, call.name, call.arguments, workdir)
                messages.add(toolResultMessage(call.id, result))
            }
            if (text.isNotBlank()) onTool("(catatan)", text)
            if (round == MAX_ROUNDS - 1) {
                return "Berhenti setelah $MAX_ROUNDS putaran tool tanpa jawaban akhir."
            }
        }
        return "Berhenti tanpa jawaban akhir."
    }

    private fun summarize(args: JSONObject): String {
        val key = when {
            args.has("command") -> "command"
            args.has("path") -> "path"
            else -> ""
        }
        return if (key.isEmpty()) args.toString().take(120) else args.optString(key).take(160)
    }

    private fun toolResultMessage(id: String, content: String) = JSONObject().apply {
        put("role", "tool")
        put("tool_call_id", id)
        put("content", content)
    }

    fun userMessage(text: String) = JSONObject().apply {
        put("role", "user")
        put("content", text)
    }

    fun assistantMessage(text: String) = JSONObject().apply {
        put("role", "assistant")
        put("content", text)
    }

    fun toolMarker(name: String, detail: String) = "→ $name: $detail"
}