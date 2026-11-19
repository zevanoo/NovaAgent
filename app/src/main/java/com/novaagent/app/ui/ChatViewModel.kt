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
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = ChatDb.of(app).chat()
    private val prefs = app.getSharedPreferences("novaagent", 0)
    private var seq = 0L

    private val sysPrompt: String
        get() = MemoryFiles.read(getApplication(), MemoryFiles.SOUL).orEmpty().ifBlank { SYSTEM_PROMPT }

    var workdir: String
        get() = Workspace.get(getApplication())
        set(value) = Workspace.set(getApplication(), value)

    var sessionId by mutableStateOf(0L)
        private set

    val messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val sessions = MutableStateFlow<List<Session>>(emptyList())
    val busy = AgentState.busy
    val status = AgentState.status
    val error = AgentState.error

    init {
        // ponytail: one session kept across restarts; per-project sessions land with M6.
        val last = prefs.getLong("sessionId", 0L)
        viewModelScope.launch {
            val existing = if (last != 0L) dao.session(last) else null
            if (existing != null) open(existing.id) else newSession()
            sessions.value = dao.sessions()
        }
        // A turn that finished while this screen was away still lands here.
        viewModelScope.launch {
            var seen = AgentState.turnDone.value
            AgentState.turnDone.collect { done ->
                if (done != seen) {
                    seen = done
                    reload()
                }
            }
        }
    }

    private suspend fun open(id: Long) {
        sessionId = id
        prefs.edit().putLong("sessionId", id).apply()
        messages.value = dao.messages(id).map { UiMessage(nextId(), it.role, it.content) }
    }

    private suspend fun reload() {
        val id = sessionId
        if (id == 0L) return
        messages.value = dao.messages(id).map { UiMessage(nextId(), it.role, it.content) }
        sessions.value = dao.sessions()
    }

    fun newSession() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val id = dao.insertSession(Session(title = "Sesi baru", createdAt = now, updatedAt = now))
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

    fun clearError() { AgentState.error.value = null }

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
            "/memory" -> appendSystem(MemoryFiles.status(getApplication()))
            "/skill" -> appendSystem(skillList())
            "/termux" -> {
                if (arg.isNotBlank()) workdir = arg
                appendSystem(
                    TermuxBridge.status(getApplication()) +
                        "\nWorking dir: " + workdir.ifBlank { "(HOME Termux)" } +
                        "\nBatas memori command: " + Limits.describe(getApplication())
                )
            }
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
            /memory — status SOUL.md / USER.md / MEMORY.md
            /skill — daftar skill terpasang
            /model — provider & model aktif
            /termux [path] — status bridge Termux, atau set working dir
        """.trimIndent()

        private const val SYSTEM_PROMPT =
            "Kamu adalah NovaAgent, coding agent di Android. Jawab ringkas, tunjukkan kode konkret."
    }
}
