package com.novaagent.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novaagent.app.agent.AgentLoop
import com.novaagent.app.bridge.TermuxBridge
import com.novaagent.app.data.ChatDb
import com.novaagent.app.data.MemoryFiles
import com.novaagent.app.data.Message
import com.novaagent.app.data.Session
import com.novaagent.app.data.Settings
import com.novaagent.app.data.UiMessage
import com.novaagent.app.data.Workspace
import com.novaagent.app.net.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = ChatDb.of(app).chat()
    private val prefs = app.getSharedPreferences("novaagent", 0)
    private var seq = 0L

    private val sysPrompt: String
        get() = MemoryFiles.read(getApplication(), MemoryFiles.SOUL).orEmpty().ifBlank { SYSTEM_PROMPT }

    /** Shell working directory for tools; empty means Termux $HOME. */
    var workdir: String
        get() = Workspace.get(getApplication())
        set(value) = Workspace.set(getApplication(), value)

    var sessionId by mutableStateOf(0L)
        private set
    val messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val sessions = MutableStateFlow<List<Session>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    init {
        // ponytail: one session kept across restarts; per-project sessions land with M6.
        val last = prefs.getLong("sessionId", 0L)
        viewModelScope.launch {
            val existing = if (last != 0L) dao.session(last) else null
            if (existing != null) open(existing.id) else newSession()
            sessions.value = dao.sessions()
        }
    }

    private suspend fun open(id: Long) {
        sessionId = id
        prefs.edit().putLong("sessionId", id).apply()
        messages.value = dao.messages(id).map { UiMessage(nextId(), it.role, it.content) }
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
        if (text.isEmpty() || busy.value) return
        if (text.startsWith("/")) {
            command(text)
            return
        }
        viewModelScope.launch {
            busy.value = true
            error.value = null
            addTurn("user", text)
            persist("user", text)
            try {
                val history = apiHistory(sessionId)
                val reply = withContext(Dispatchers.IO) {
                    AgentLoop.run(
                        context = getApplication(),
                        history = history,
                        system = sysPrompt,
                        workdir = workdir,
                        onTool = { name, detail -> addTurn("tool", AgentLoop.toolMarker(name, detail)) },
                    )
                }
                addTurn("assistant", reply)
                persist("assistant", reply)
            } catch (e: Exception) {
                error.value = e.message ?: e.javaClass.simpleName
            } finally {
                busy.value = false
                sessions.value = dao.sessions()
            }
        }
    }

    /** Rebuilds the provider-facing history from Room. Tool markers are UI-only and get dropped. */
    private suspend fun apiHistory(id: Long): MutableList<JSONObject> =
        dao.messages(id).mapNotNull { row ->
            when (row.role) {
                "user" -> AgentLoop.userMessage(row.content)
                "assistant" -> row.content.takeIf { it.isNotBlank() }?.let { AgentLoop.assistantMessage(it) }
                else -> null
            }
        }.toMutableList()

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

    private fun appendSystem(text: String) {
        addTurn("assistant", text)
    }

    fun clearError() { error.value = null }

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
                appendSystem(TermuxBridge.status(getApplication()) + "\nWorking dir: " + workdir.ifBlank { "(HOME Termux)" })
            }
            "/compact" -> compact()
            else -> appendSystem("Command tidak dikenal: $name\n\n$HELP")
        }
    }

    private fun compact() = viewModelScope.launch {
        val current = messages.value.filter { it.role != "tool" }
        if (current.size < 2) {
            appendSystem("Riwayat terlalu pendek untuk diringkas.")
            return@launch
        }
        busy.value = true
        error.value = null
        try {
            val summary = withContext(Dispatchers.IO) {
                val history = apiHistory(sessionId)
                history.add(
                    AgentLoop.userMessage(
                        "Ringkas percakapan di atas jadi catatan kerja padat: keputusan, kode/nama file " +
                            "yang disepakati, dan langkah berikutnya. Bahasa Indonesia, maksimal 20 baris."
                    )
                )
                LlmClient.chat(Settings.config.value, history, sysPrompt)
            }
            dao.clearMessages(sessionId)
            val note = "[compact]\n$summary"
            dao.insertMessage(
                Message(sessionId = sessionId, role = "assistant", content = note, createdAt = System.currentTimeMillis())
            )
            messages.value = listOf(UiMessage(nextId(), "assistant", note))
        } catch (e: Exception) {
            error.value = e.message ?: e.javaClass.simpleName
        } finally {
            busy.value = false
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
