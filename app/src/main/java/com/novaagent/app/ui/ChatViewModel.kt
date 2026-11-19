package com.novaagent.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novaagent.app.agent.AgentLoop
import com.novaagent.app.bridge.TermuxBridge
import com.novaagent.app.data.AgentState
import com.novaagent.app.data.ChatDb
import com.novaagent.app.data.Limits
import com.novaagent.app.data.MemoryFiles
import com.novaagent.app.data.Message
import com.novaagent.app.data.Session
import com.novaagent.app.data.Settings
import com.novaagent.app.data.UiMessage
import com.novaagent.app.data.UiRound
import com.novaagent.app.data.Workspace
import com.novaagent.app.net.LlmClient
import com.novaagent.app.service.AgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Chat UI state. The agent turn itself runs in [AgentService] so it survives the
 * Activity; this class only writes the user message and re-reads Room when the
 * service signals completion.
 *
 * Working directory is three-tier (session > project > main). The session tier is
 * per-chat, so a project session can stay pinned while the project default changes.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = ChatDb.of(app).chat()
    private val prefs = app.getSharedPreferences("novaagent", 0)
    private var seq = 0L

    private val sysPrompt: String
        get() = MemoryFiles.systemPrompt(getApplication(), workdir).text

    /** Resolved workdir for the open session; setting it writes the session tier only. */
    var workdir: String
        get() = sessionWorkdir.ifBlank { Workspace.resolve(getApplication()) }
        set(value) {
            sessionWorkdir = value.trim()
            val id = sessionId
            if (id != 0L) viewModelScope.launch {
                dao.setSessionWorkdir(id, sessionWorkdir)
                if (sessionWorkdir.isNotBlank()) Workspace.remember(getApplication(), sessionWorkdir)
            }
        }

    var sessionWorkdir by mutableStateOf("")
        private set

    /** Which tier produced [workdir] — "sesi" / "project" / "main" / "HOME Termux". */
    val pendingMemory = AgentState.pendingMemory

    /** Memory writes the agent queued and the user has not answered yet. */
    fun pendingWrites(): List<MemoryFiles.Pending> = MemoryFiles.pending(getApplication())

    fun workdirSource(): String = when {
        sessionWorkdir.isNotBlank() -> "sesi"
        else -> Workspace.source(getApplication())
    }

    fun promotedWorkdir(): String = Workspace.main(getApplication())

    fun projectWorkdir(): String = Workspace.project(getApplication())

    fun setProjectWorkdir(path: String) = Workspace.setProject(getApplication(), path)

    fun setMainWorkdir(path: String) = Workspace.setMain(getApplication(), path)

    /** Folders offered in the session picker: projects seen so far. */
    fun knownWorkdirs(): List<String> = Workspace.known(getApplication())

    var sessionId by mutableStateOf(0L)
        private set

    val messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val sessions = MutableStateFlow<List<Session>>(emptyList())
    val busy = AgentState.busy
    val status = AgentState.status
    val error = AgentState.error
    val limitHit = AgentState.limitHit

    init {
        val last = prefs.getLong("sessionId", 0L)
        viewModelScope.launch {
            val existing = if (last != 0L) dao.session(last) else null
            if (existing != null) open(existing.id) else newSession()
            sessions.value = dao.sessions()
        }
        var seen = AgentState.turnDone.value
        viewModelScope.launch {
            AgentState.turnDone.collect { done ->
                if (done != seen) {
                    seen = done
                    reload()
                }
            }
        }
        // "Prompt baru" tapped in the limit notification: focus the composer on a new session.
        var seenPrompt = AgentState.requestPrompt.value
        viewModelScope.launch {
            AgentState.requestPrompt.collect { n ->
                if (n != seenPrompt) {
                    seenPrompt = n
                    newSession()
                }
            }
        }
    }

    private suspend fun open(id: Long) {
        sessionId = id
        prefs.edit().putLong("sessionId", id).apply()
        sessionWorkdir = dao.session(id)?.workdir.orEmpty()
        AgentState.limitHit.value = null
        messages.value = render(dao.messages(id))
    }

    private suspend fun reload() {
        val id = sessionId
        if (id == 0L) return
        messages.value = render(dao.messages(id))
        sessions.value = dao.sessions()
    }

    /**
     * Tool rounds belong under the user turn that triggered them, so the assistant
     * row carries them and the renderer attaches them to the preceding user bubble.
     */
    private fun render(rows: List<Message>): List<UiMessage> = rows.map { row ->
        UiMessage(
            id = row.id,
            role = row.role,
            text = row.content,
            rounds = decodeRounds(row.rounds),
        )
    }

    private fun decodeRounds(raw: String): List<UiRound> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UiRound(
                    name = o.optString("name"),
                    detail = o.optString("detail"),
                    thought = o.optString("thought"),
                    result = o.optString("result"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun newSession() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val id = dao.insertSession(
            Session(title = "Sesi baru", createdAt = now, updatedAt = now, workdir = "")
        )
        open(id)
        sessions.value = dao.sessions()
    }

    fun openSession(id: Long) = viewModelScope.launch { open(id); sessions.value = dao.sessions() }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (text.startsWith("/")) {
            command(text)
            return
        }
        if (busy.value) return
        viewModelScope.launch {
            AgentState.error.value = null
            addTurn("user", text)
            persist("user", text)
            AgentService.startTurn(getApplication(), sessionId)
        }
    }

    /** "Lanjut" after the round cap: same thread, fresh budget, no new user row. */
    fun continueTurn() {
        if (busy.value) return
        AgentState.limitHit.value = null
        AgentState.error.value = null
        AgentService.continueTurn(getApplication(), sessionId)
    }

    fun dismissLimit() {
        AgentState.limitHit.value = null
    }

    fun stopTurn() {
        val context = getApplication<Application>()
        context.stopService(android.content.Intent(context, AgentService::class.java))
        AgentState.busy.value = false
        AgentState.status.value = ""
        viewModelScope.launch { reload() }
    }

    private fun addTurn(role: String, text: String) {
        messages.value = messages.value + UiMessage(nextId(), role, text)
    }

    private fun nextId(): Long = ++seq

    private suspend fun persist(role: String, text: String) {
        val id = sessionId
        dao.insertMessage(Message(sessionId = id, role = role, content = text, createdAt = System.currentTimeMillis()))
        val title = messages.value.firstOrNull { it.role == "user" }?.text?.take(40) ?: "Sesi baru"
        dao.touchSession(id, title, System.currentTimeMillis())
    }

    private fun appendSystem(text: String) = addTurn("assistant", text)

    fun clearError() {
        AgentState.error.value = null
    }

    private fun command(text: String) {
        val name = text.substringBefore(' ').lowercase()
        val arg = text.substringAfter(' ', "").trim()
        when (name) {
            "/help" -> appendSystem(HELP)
            "/clear" -> newSession()
            "/model" -> appendSystem(
                "Provider: ${Settings.config.value.baseUrl}\nModel: ${Settings.config.value.model}\n" +
                    "Ubah di tombol ⚙ di kanan atas."
            )
            "/memory" -> memoryCommand(arg)
            "/skill" -> appendSystem(skillList())
            "/termux" -> {
                if (arg.isNotBlank()) workdir = arg
                appendSystem(
                    TermuxBridge.status(getApplication()) +
                        "\nWorking dir: ${workdir.ifBlank { "(HOME Termux)" }} (dari ${workdirSource()})" +
                        "\nBatas memori command: " + Limits.describeMemory(getApplication()) +
                        "\nBatas putaran tool per turn: " + Limits.maxRounds(getApplication())
                )
            }
            "/limit" -> appendSystem(
                "Batas putaran tool: ${Limits.maxRounds(getApplication())}\n" +
                    "Batas memori command: ${Limits.describeMemory(getApplication())}\n" +
                    "Ubah di Settings."
            )
            "/compact" -> compact()
            else -> appendSystem("Command tidak dikenal: $name\n\n$HELP")
        }
    }

    private fun compact() = viewModelScope.launch {
        val id = sessionId
        if (id == 0L) return@launch
        val rows = dao.messages(id)
        if (rows.size < 2) {
            appendSystem("Riwayat terlalu pendek untuk diringkas.")
            return@launch
        }
        AgentState.busy.value = true
        AgentState.error.value = null
        try {
            val history = rows.mapNotNull { row ->
                when (row.role) {
                    "user" -> AgentLoop.userMessage(row.content)
                    "assistant" -> row.content.takeIf { it.isNotBlank() }?.let { AgentLoop.assistantMessage(it) }
                    else -> null
                }
            }.toMutableList()
            history.add(
                AgentLoop.userMessage(
                    "Ringkas percakapan di atas jadi catatan kerja padat: keputusan, kode/nama file " +
                        "yang disepakati, dan langkah berikutnya. Bahasa Indonesia, maksimal 20 baris."
                )
            )
            val summary = withContext(Dispatchers.IO) {
                LlmClient.chat(Settings.config.value, history, sysPrompt)
            }
            dao.clearMessages(id)
            dao.insertMessage(
                Message(
                    sessionId = id,
                    role = "assistant",
                    content = "[compact]\n$summary",
                    createdAt = System.currentTimeMillis(),
                )
            )
            reload()
        } catch (e: Exception) {
            AgentState.error.value = e.message ?: e.javaClass.simpleName
        } finally {
            AgentState.busy.value = false
        }
    }

    /**
     * /memory                       status of all three files
     * /memory <soul|user|project> <teks>   append a note
     * /memory approve <target>      apply a write the agent queued
     * /memory reject <target>       drop it
     */
    private fun memoryCommand(arg: String) = viewModelScope.launch {
        val app = getApplication<Application>()
        val parts = arg.trim().split(Regex("\\s+"), limit = 2)
        val head = parts.getOrNull(0)?.lowercase().orEmpty()
        val rest = parts.getOrNull(1)?.trim().orEmpty()
        val text = withContext(Dispatchers.IO) {
            when {
                arg.isBlank() -> MemoryFiles.status(app, workdir) + pendingSummary(app)
                head == "approve" -> MemoryFiles.confirmPending(app, rest.uppercase())
                head == "reject" -> {
                    MemoryFiles.rejectPending(app, rest.uppercase())
                    "Ditolak: catatan $rest tidak disimpan."
                }
                head == "edit" -> {
                    val (file, body) = MemoryFiles.readTarget(app, workdir, rest)
                        ?: return@withContext "Error: target harus SOUL, USER, atau PROJECT."
                    "Edit langsung di Settings → Memory, atau salin isi file:\n${file.path}\n\n$body"
                }
                head == "soul" || head == "user" || head == "project" -> {
                    if (rest.isBlank()) "Error: isi catatan kosong. Contoh: /memory user suka Kotlin"
                    else MemoryFiles.append(app, workdir, head, rest)
                }
                else -> "Error: format /memory [soul|user|project|approve|reject|edit] [teks]"
            }
        }
        if (head == "approve" && text.startsWith("OK:")) AgentState.pendingMemory.value += 1
        appendSystem(text)
    }

    private fun pendingSummary(app: Application): String {
        val pending = MemoryFiles.pending(app)
        if (pending.isEmpty()) return ""
        return "\n\nMenunggu konfirmasi:\n" + pending.joinToString("\n") {
            "• ${it.target}: ${it.note.take(100)}\n  /memory approve ${it.target.lowercase()} — atau reject"
        }
    }

    /** Applies or drops the memory the agent queued. Called from the chat banner. */
    fun resolvePending(target: String, approve: Boolean) = viewModelScope.launch {
        val app = getApplication<Application>()
        val result = withContext(Dispatchers.IO) {
            if (approve) MemoryFiles.confirmPending(app, target) else {
                MemoryFiles.rejectPending(app, target)
                "Ditolak: catatan $target tidak disimpan."
            }
        }
        AgentState.pendingMemory.value += 1
        appendSystem(result)
    }

    private fun skillList(): String {
        val dir = File(getApplication<Application>().filesDir, "skills")
        val names = dir.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
        return if (names.isEmpty()) {
            "Belum ada skill terpasang.\nFolder: ${dir.absolutePath}\nLoader skill ada di M5."
        } else {
            names.sorted().joinToString("\n") { "• $it" }
        }
    }

    companion object {
        private val HELP = """
            Command tersedia:
            /help — daftar ini
            /compact — ringkas riwayat jadi catatan kerja
            /clear — mulai sesi baru
            /memory — status memory global & project
            /memory <soul|user|project> <teks> — tambah catatan
            /memory approve|reject <target> — terapkan/buang catatan tertunda
            /skill — daftar skill terpasang
            /model — provider & model aktif
            /termux [path] — status bridge, atau set workdir sesi
            /limit — batas putaran tool & memori
        """.trimIndent()
    }
}
