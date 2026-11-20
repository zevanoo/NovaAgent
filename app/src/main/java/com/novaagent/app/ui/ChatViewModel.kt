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
import com.novaagent.app.data.I18n
import com.novaagent.app.data.Session
import com.novaagent.app.data.SessionTitle
import com.novaagent.app.data.Snapshots
import com.novaagent.app.data.Settings
import com.novaagent.app.data.Skills
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
    /** Counts down: optimistic rows get -1, -2, ... */
    private var seq = 0L

    val pendingMemory = AgentState.pendingMemory

    /** Memory writes the agent queued and the user has not answered yet. */
    fun pendingWrites(): List<MemoryFiles.Pending> = MemoryFiles.pending(getApplication())

    /** Folder the open session works in; the agent creates it on first write. */
    fun workdirLabel(): String = Workspace.display(getApplication(), sessionId)

    /** Opens the session folder in the system file manager, if one is installed. */
    fun openWorkdir() {
        val path = workdirLabel()
        val uri = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" +
            android.net.Uri.encode(path.removePrefix("/storage/emulated/0/")))
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "vnd.android.document/directory")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure {
                AgentState.error.value = "Tidak ada file manager yang bisa membuka $path"
            }
    }

    /** Re-read the folder label after a turn: the agent may have just created one. */
    fun refreshWorkdir() {
        AgentState.sessionDir.value = Workspace.sessionDir(getApplication(), sessionId)
    }

    fun defaultWorkdir(): String = Workspace.defaultDir(getApplication())

    fun setDefaultWorkdir(path: String) {
        Workspace.setDefaultDir(getApplication(), path)
        bumpWorkdir()
    }

    /** Invalidate the header labels after the agent created a folder or the user changed the default. */
    private fun bumpWorkdir() {
        AgentState.workdirVersion.value += 1
    }

    fun skills(): List<Skills.Skill> = Skills.all(getApplication(), Workspace.resolve(getApplication(), sessionId))

    fun skillStatus(): String {
        val list = skills()
        return if (list.isEmpty()) {
            "Belum ada skill terpasang.\nInstal lewat Settings → Skill, atau taruh folder skill di " +
                com.novaagent.app.data.AppFiles.skills(getApplication()).absolutePath
        } else {
            list.joinToString("\n") { "• ${it.name} — ${it.description.take(90)}" }
        }
    }

    /**
     * 0 means the draft screen: not a session yet. The app opens here on purpose —
     * a session only exists once the user sends the first prompt, so the drawer
     * never fills with empty rows.
     */
    var sessionId by mutableStateOf(0L)
        private set

    /** Random greeting for the draft screen so the app never opens blank. */
    var welcome by mutableStateOf("")
        private set

    /** Title in the app bar: the session's own title, blank on a draft. */
    val sessionTitle = MutableStateFlow("")

    val lang = MutableStateFlow(I18n.current(app))

    val messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val sessions = MutableStateFlow<List<Session>>(emptyList())
    val busy = AgentState.busy
    val status = AgentState.status
    val error = AgentState.error
    val limitHit = AgentState.limitHit

    init {
        welcome = I18n.welcome(app)
        // After the app was killed and reopened, AgentState is empty but the
        // service may still be mid-turn. Ask it, so the progress row and the Stop
        // button come back instead of showing an idle chat that rejects retry.
        viewModelScope.launch {
            AgentState.reportedSession.collect { active ->
                when {
                    active > 0L -> {
                        AgentState.busy.value = true
                        if (sessionId == 0L || sessionId == active) {
                            if (active != sessionId) open(active)
                        }
                    }
                    active == 0L -> AgentState.busy.value = false
                }
            }
        }
        viewModelScope.launch {
            // Reopen the last session only if it has messages; an empty one was a
            // draft that never became real, so the user lands on a fresh draft.
            val last = prefs.getLong("sessionId", 0L)
            val existing = if (last != 0L) dao.session(last) else null
            if (existing != null && dao.messages(last).isNotEmpty()) open(last) else startDraft()
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
        AgentState.limitHit.value = null
        AgentState.sessionDir.value = Workspace.sessionDir(getApplication(), id)
        messages.value = render(dao.messages(id))
        sessionTitle.value = dao.title(id).orEmpty()
    }

    /** Back to the landing page: no session row exists while the draft is open. */
    fun startDraft() {
        sessionId = 0L
        welcome = I18n.welcome(getApplication())
        messages.value = emptyList()
        sessionTitle.value = ""
        AgentState.limitHit.value = null
        AgentState.sessionDir.value = Workspace.defaultDir(getApplication())
        AgentState.workdirVersion.value += 1
        prefs.edit().remove("sessionId").apply()
    }

    private suspend fun reload() {
        val id = sessionId
        if (id == 0L) return
        messages.value = render(dao.messages(id))
        sessionTitle.value = dao.title(id).orEmpty()
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

    /** "New session" in the drawer just goes back to the landing page. */
    fun newSession() = startDraft()

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
            // First prompt on the draft page is what creates the session, so no
            // empty row ever reaches the drawer.
            if (sessionId == 0L) {
                val now = System.currentTimeMillis()
                val id = dao.insertSession(
                    Session(
                        title = SessionTitle.fromPrompt(text),
                        createdAt = now,
                        updatedAt = now,
                        workdir = "",
                    )
                )
                open(id)
            }
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

    /** Re-reads the language after Settings changed it. */
    fun reloadLanguage() {
        lang.value = I18n.current(getApplication())
        if (sessionId == 0L) welcome = I18n.welcome(getApplication())
    }

    fun dismissLimit() {
        AgentState.limitHit.value = null
    }

    /** Hard stop: kills the running turn and the service that owns it. */
    fun stopTurn() {
        val context = getApplication<Application>()
        context.stopService(android.content.Intent(context, AgentService::class.java))
        AgentState.busy.value = false
        AgentState.status.value = ""
        viewModelScope.launch { reload() }
    }

    /**
     * Soft interrupt: the loop stops at the next tool boundary and keeps whatever
     * it already produced. Preferred over [stopTurn] when a command is still
     * running and its output is worth keeping.
     */
    fun interruptTurn() {
        AgentState.interrupt.value = true
        AgentState.status.value = "Menghentikan setelah langkah ini…"
    }

    /** Re-runs the turn that produced [message], from the prompt that opened it. */
    fun retry(message: UiMessage) = viewModelScope.launch {
        // The cleared check below is authoritative: a stale busy flag from a dead
        // process used to block retry forever.
        AgentService.queryState(getApplication())
        kotlinx.coroutines.delay(120)
        if (AgentState.running.value && AgentState.session.value == sessionId) return@launch
        val id = sessionId
        val rows = dao.messages(id)
        val index = rows.indexOfFirst { it.id == message.id }
        if (index < 0) return@launch
        // The prompt to replay is the user row at or before the tapped message.
        val prompt = rows.take(index + 1).lastOrNull { it.role == "user" } ?: return@launch
        // Files the prompt changed are left alone here; retry only replays it.
        dao.deleteMessagesFrom(id, prompt.id)
        AgentState.error.value = null
        AgentState.limitHit.value = null
        reload()
        AgentService.startTurn(getApplication(), id)
    }

    /**
     * Revert one prompt: undo every file change made by that turn, then drop the
     * prompt and everything after it. The snapshot is the git commit taken just
     * before the turn ran, so "undo" is a checkout rather than a guess.
     */
    fun revertPrompt(message: UiMessage, onResult: (String) -> Unit = {}) = viewModelScope.launch {
        if (busy.value) return@launch
        val app = getApplication<Application>()
        val id = sessionId
        val rows = dao.messages(id)
        val index = rows.indexOfFirst { it.id == message.id }
        if (index < 0) return@launch
        val prompt = rows.take(index + 1).lastOrNull { it.role == "user" } ?: return@launch

        val workdir = Workspace.resolve(app, id)
        val snapshot = prompt.snapshot
        val note = withContext(Dispatchers.IO) {
            when {
                workdir.isBlank() -> "Error: workdir belum ada."
                snapshot.isBlank() -> "Tidak ada snapshot file; hanya pesan yang dihapus."
                else -> Snapshots.restore(app, workdir, snapshot)
            }
        }
        onResult(note)
        dao.deleteMessagesFrom(id, prompt.id)
        AgentState.limitHit.value = null
        reload()
    }

    fun deleteMessage(message: UiMessage) = viewModelScope.launch {
        dao.deleteMessage(message.id)
        reload()
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        if (busy.value && id == sessionId) return@launch
        dao.deleteSession(id)
        Workspace.forgetSession(getApplication(), id)
        val remaining = dao.sessions()
        sessions.value = remaining
        if (id == sessionId) {
            val next = remaining.firstOrNull()
            if (next != null) open(next.id) else startDraft()
        }
    }

    private fun addTurn(role: String, text: String) {
        messages.value = messages.value + UiMessage(nextId(), role, text)
    }

    /**
     * Temporary ids count DOWN from -1. Room's autoincrement only issues positive
     * ids, so an optimistic row can never collide with a persisted one and trip
     * LazyColumn's "key was already used" check.
     */
    private fun nextId(): Long = --seq

    private suspend fun persist(role: String, text: String) {
        val id = sessionId
        dao.insertMessage(Message(sessionId = id, role = role, content = text, createdAt = System.currentTimeMillis()))
        // The title was set from the first prompt when the session was created;
        // later turns only move it up the list.
        val title = sessionTitle.value.ifBlank { SessionTitle.fromPrompt(text) }
        dao.touchSession(id, title, System.currentTimeMillis())
        sessionTitle.value = title
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
            "/skill" -> appendSystem(skillStatus())
            "/termux" -> {
                if (arg.isNotBlank()) setDefaultWorkdir(arg)
                appendSystem(
                    TermuxBridge.status(getApplication()) +
                        "\nWorkdir sesi: ${workdirLabel()}" +
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
                LlmClient.chat(Settings.config.value, history, MemoryFiles.systemPrompt(getApplication(), Workspace.resolve(getApplication(), id)).text)
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
        val id = sessionId
        val parts = arg.trim().split(Regex("\\s+"), limit = 2)
        val head = parts.getOrNull(0)?.lowercase().orEmpty()
        val rest = parts.getOrNull(1)?.trim().orEmpty()
        val text = withContext(Dispatchers.IO) {
            when {
                arg.isBlank() -> MemoryFiles.status(app, Workspace.resolve(app, id)) + pendingSummary(app)
                head == "approve" -> MemoryFiles.confirmPending(app, rest.uppercase())
                head == "reject" -> {
                    MemoryFiles.rejectPending(app, rest.uppercase())
                    "Ditolak: catatan $rest tidak disimpan."
                }
                head == "edit" -> {
                    val (file, body) = MemoryFiles.readTarget(app, Workspace.resolve(app, id), rest)
                        ?: return@withContext "Error: target harus SOUL, USER, atau PROJECT."
                    "Edit langsung di Settings → Memory, atau salin isi file:\n${file.path}\n\n$body"
                }
                head == "soul" || head == "user" || head == "project" -> {
                    if (rest.isBlank()) "Error: isi catatan kosong. Contoh: /memory user suka Kotlin"
                    else MemoryFiles.append(app, Workspace.resolve(app, id), head, rest)
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
