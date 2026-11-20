package com.novaagent.app.agent

import android.content.Context
import com.novaagent.app.bridge.TermuxBridge
import com.novaagent.app.data.AgentState
import com.novaagent.app.data.AppFiles
import com.novaagent.app.data.Limits
import com.novaagent.app.data.MemoryFiles
import com.novaagent.app.data.Skills
import com.novaagent.app.net.LlmClient
import com.novaagent.app.net.McpClient
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

    /**
     * Paths `read_file` / `write_file` / `list_dir` may touch: the app sandbox and
     * nothing else. Same rule the shell enforces, so there is exactly one boundary
     * to reason about and no way to read the user's photos with a typo.
     */
    private fun allowedRoot(context: Context): String = AppFiles.root(context).absolutePath

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
        put(tool(
            "use_skill",
            "Muat isi skill. System prompt hanya memuat daftar nama+deskripsi; panggil " +
                "tool ini untuk membaca instruksi lengkapnya sebelum mengerjakan task " +
                "yang cocok dengan skill tersebut.",
            JSONObject().apply { prop("name", "string", "Nama skill dari daftar") },
            listOf("name"),
        ))
        put(tool(
            "save_memory",
            "Simpan catatan ke memory. target=USER, SOUL, atau PROJECT.",
            JSONObject().apply {
                prop("target", "string", "USER, SOUL, atau PROJECT")
                prop("note", "string", "Catatan singkat yang disimpan")
            },
            listOf("target", "note"),
        ))
        put(tool(
            "mcp_call",
            "Panggil tool MCP yang sudah dikonfigurasi. Discovery server MCP tetap dibatasi URL HTTP(S) lokal/HTTPS.",
            JSONObject().apply {
                prop("server", "string", "Nama server MCP")
                prop("tool", "string", "Nama tool MCP")
                prop("arguments", "object", "Argumen tool")
            },
            listOf("server", "tool"),
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
    fun execute(
        context: Context,
        name: String,
        args: JSONObject,
        workdir: String,
        timeoutMs: Int = DEFAULT_COMMAND_TIMEOUT_MS,
        ensureSessionDir: () -> String = { workdir },
    ): String = try {
        // Mutating tools get the session's own project folder, created on first use.
        // Read-only tools stay on the default dir so a question does not create a folder.
        val dir = if (name == "write_file" || name == "run_shell" || name == "save_memory") {
            ensureSessionDir().ifBlank { workdir }
        } else {
            workdir
        }
        if (name.startsWith("mcp_")) {
            return McpClient.invoke(context, name, args)
        }
        when (name) {
            "read_file" -> readFile(context, args.optString("path"), args, dir)
            "write_file" -> writeFile(context, args.optString("path"), args.optString("content"), dir)
            "list_dir" -> listDir(context, args.optString("path"), dir)
            "run_shell" -> runShell(context, args, timeoutMs, dir)
            "mcp_call" -> McpClient.call(
                context,
                args.optString("server"),
                args.optString("tool"),
                args.optJSONObject("arguments") ?: JSONObject(),
            )
            "save_memory" -> saveMemory(context, args.optString("target"), args.optString("note"), dir)
            "use_skill" -> Skills.body(context, dir, args.optString("name"))
            else -> "Error: tool tidak dikenal '$name'. Tersedia: read_file, write_file, list_dir, run_shell, save_memory, use_skill."
        }
    } catch (e: Exception) {
        "Error: " + (e.message ?: e.javaClass.simpleName)
    }

    const val DEFAULT_COMMAND_TIMEOUT_MS = 120_000

    private fun readFile(context: Context, path: String, args: JSONObject, workdir: String): String {
        val f = resolve(context, path, workdir)
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

    private fun writeFile(context: Context, path: String, content: String, workdir: String): String {
        val f = resolve(context, path, workdir)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "OK: ${f.path} ditulis (${content.length} karakter)."
    }

    private fun listDir(context: Context, path: String, workdir: String): String {
        val f = resolve(context, path, workdir)
        if (!f.isDirectory) return "Error: bukan directory: ${f.path}"
        val entries = f.listFiles()?.sortedBy { it.name }?.joinToString("\n") {
            (if (it.isDirectory) "d " else "f ") + it.name
        }.orEmpty()
        return if (entries.isBlank()) "(kosong) ${f.path}" else entries
    }

    private fun runShell(context: Context, args: JSONObject, timeoutMs: Int, cwd: String): String {
        val command = args.optString("command")
        if (command.isBlank()) return "Error: command kosong."
        if (!com.novaagent.app.data.CommandApproval.await(command)) return "Rejected by user or approval timeout."
        val where = args.optString("cwd").trim().ifBlank { cwd }
        val capMb = Limits.memoryCapMb(context)

        // ulimit -v takes KB. Without a cap one runaway command can get the whole
        // app OOM-killed by the system; with it, only the command dies.
        val body = if (capMb > 0) "ulimit -v ${capMb * 1024} 2>/dev/null; $command" else command

        // The banner tells the model where the command ran, so a relative-path
        // mistake is obvious instead of mysterious.
        val banner = if (where.isBlank()) "" else "cwd: $where\n"
        val r = TermuxBridge.run(context, body, timeoutMs.coerceIn(1_000, 3_600_000), where)
        return when {
            r.error != null -> "Error: ${r.error}"
            r.exitCode == 0 -> banner + r.output.ifBlank { "(selesai, tanpa output)" }
            r.exitCode == 137 || r.output.contains("Cannot allocate memory") ->
                "Error: perintah dihentikan karena melewati batas memori $capMb MB " +
                    "(naikkan di Settings kalau memang butuh).\n${r.output}".trim()
            else -> banner + "exit ${r.exitCode}\n${r.output}".take(MAX_OUTPUT + 40)
        }
    }

    /**
     * Memory write. With the confirm policy the note is queued for the user instead
     * of landing silently — memory is injected into every later prompt, so a wrong
     * write would poison future sessions, not just this turn.
     */
    private fun saveMemory(context: Context, target: String, note: String, workdir: String): String {
        val t = target.trim().uppercase()
        if (t !in setOf("SOUL", "USER", "PROJECT")) return "Error: target harus SOUL, USER, atau PROJECT."
        if (note.isBlank()) return "Error: note kosong."
        if (t == "PROJECT" && workdir.isBlank()) {
            return "Error: workdir belum diset, tidak ada project untuk disimpan."
        }
        if (!Limits.isAutoWrite(context)) {
            MemoryFiles.queuePending(context, workdir, t, note)
            AgentState.pendingMemory.value += 1
            return "Menunggu konfirmasi user: $t — catatan disimpan setelah disetujui di chat."
        }
        return MemoryFiles.append(context, workdir, t, note)
    }

    private fun resolve(context: Context, path: String, workdir: String): File {
        val root = allowedRoot(context)
        val home = AppFiles.shellHome(context).absolutePath
        val raw = path.trim().ifBlank { workdir }
        val expanded = when {
            raw == "~" -> home
            raw.startsWith("~/") -> home + raw.removePrefix("~")
            raw.startsWith("/") -> raw
            workdir.isNotBlank() -> "$workdir/$raw"
            else -> "$home/$raw"
        }
        val f = File(expanded).canonicalFile
        if (!AppFiles.isInsideSandbox(context, f.path)) {
            throw SecurityException(
                "Path di luar workdir agent: ${f.path}. Hanya $root yang bisa diakses."
            )
        }
        return f
    }
    private const val MAX_OUTPUT = 8_000
}

/** One assistant→tools exchange, kept so the UI can render a collapsible row. */
data class ToolRound(val name: String, val detail: String, val thought: String, val result: String)

/** Outcome of one user turn. */
data class TurnResult(
    val text: String,
    val rounds: List<ToolRound>,
    val hitLimit: Boolean,
    /** Provider-shaped history after the turn, so the next turn keeps the thread. */
    val history: List<JSONObject>,
    /** True when the user stopped it: a normal partial result, not an error. */
    val interrupted: Boolean = false,
)

/**
 * Drives one assistant turn: call the model, run the tools it asks for, repeat
 * until it answers or [maxRounds] tool rounds are spent.
 *
 * No wall-clock watchdog on purpose — long builds and installs are legitimate.
 * The round cap is the brake: when it trips, the service asks the user whether
 * to continue ("lanjut") or start a fresh prompt.
 */
object AgentLoop {

    fun run(
        context: Context,
        history: List<JSONObject>,
        system: String,
        workdir: String,
        maxRounds: Int,
        commandTimeoutMs: Int = AgentTools.DEFAULT_COMMAND_TIMEOUT_MS,
        ensureSessionDir: () -> String = { workdir },
        onTool: (ToolRound) -> Unit,
    ): TurnResult {
        val messages = ArrayList(history)
        val rounds = mutableListOf<ToolRound>()
        var used = 0

        while (used < maxRounds) {
            // Interrupt boundary: only between batches, never mid-batch. A tool
            // batch the provider already committed to must be answered in full or
            // the next request is rejected for missing tool results.
            if (AgentState.interrupt.value) {
                return TurnResult(
                    text = interruptedText(rounds),
                    rounds = rounds,
                    hitLimit = false,
                    history = prune(messages),
                    interrupted = true,
                )
            }
            val response = LlmClient.chatWithTools(
                com.novaagent.app.data.Settings.config.value,
                messages,
                system,
                JSONArray(AgentTools.defs).let { base ->
                    val mcp = runCatching { com.novaagent.app.net.McpClient.definitions(context) }.getOrDefault(JSONArray())
                    (0 until mcp.length()).forEach { base.put(mcp.get(it)) }
                    base.toString()
                },
            )
            val text = response.content.trim()

            if (response.toolCalls.isEmpty()) {
                messages.add(response.raw)
                return TurnResult(
                    text = text.ifBlank { "(provider mengembalikan balasan kosong)" },
                    rounds = rounds,
                    hitLimit = false,
                    history = prune(messages),
                )
            }

            messages.add(response.raw)
            for (call in response.toolCalls) {
                used++
                val timeout = if (call.arguments.has("timeout_ms")) {
                    call.arguments.optInt("timeout_ms", commandTimeoutMs)
                } else {
                    commandTimeoutMs
                }
                // run_shell blocks on Termux's reply, which can be minutes; running
                // it on the caller's thread would freeze whatever dispatcher that is.
                val result = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    AgentTools.execute(
                        context, call.name, call.arguments, workdir, timeout, ensureSessionDir,
                    )
                }
                messages.add(toolResultMessage(call.id, result))
                val round = ToolRound(call.name, summarize(call.arguments), text, result)
                rounds += round
                onTool(round)
                if (used >= maxRounds) break
            }
            // The user may have pressed interrupt while that command was running.
            // Every result is already in `messages`, so stopping here is safe.
            if (AgentState.interrupt.value) {
                return TurnResult(
                    text = interruptedText(rounds),
                    rounds = rounds,
                    hitLimit = false,
                    history = prune(messages),
                    interrupted = true,
                )
            }
        }

        return TurnResult(
            text = "Batas $maxRounds putaran tool tercapai. Balas \"lanjut\" untuk meneruskan, " +
                "atau kirim prompt baru.",
            rounds = rounds,
            hitLimit = true,
            history = prune(messages),
        )
    }

    /** What the transcript shows when the user stopped the turn. */
    private fun interruptedText(rounds: List<ToolRound>): String =
        if (rounds.isEmpty()) "Dihentikan sebelum ada langkah yang berjalan."
        else "Dihentikan setelah ${rounds.size} langkah. Hasil di atas tetap tersimpan."

    /** Keeps the provider request bounded: trim the middle, keep the opening context. */
    private fun prune(messages: List<JSONObject>): List<JSONObject> {
        if (messages.size <= MAX_HISTORY) return messages
        return messages.take(2) + messages.takeLast(MAX_HISTORY - 2)
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

    private const val MAX_HISTORY = 60
}