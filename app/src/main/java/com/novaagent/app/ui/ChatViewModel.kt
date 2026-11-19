package com.novaagent.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novaagent.app.data.ChatDb
import com.novaagent.app.data.MemoryFiles
import com.novaagent.app.data.Message
import com.novaagent.app.data.Session
import com.novaagent.app.data.Settings
import com.novaagent.app.data.UiMessage
import com.novaagent.app.net.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val db = ChatDb.of(app)
    private val dao = db.chat()
    private val prefs = app.getSharedPreferences("novaagent", 0)
    private val sysPrompt: String
        get() = MemoryFiles.read(getApplication(), MemoryFiles.SOUL).orEmpty().ifBlank { SYSTEM_PROMPT }

    var sessionId by mutableStateOf(0L)
        private set
    val messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val sessions = MutableStateFlow<List<Session>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    init {
        // ponytail: one session kept across restarts; multi-project sessions land with M6.
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
        messages.value = dao.messages(id).map { UiMessage(it.role, it.content) }
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
            val history = messages.value + UiMessage("user", text)
            messages.value = history
            persist("user", text)
            try {
                val reply = withContext(Dispatchers.IO) { LlmClient.chat(Settings.config.value, history, sysPrompt) }
                messages.value = messages.value + UiMessage("assistant", reply)
                persist("assistant", reply)
            } catch (e: Exception) {
                error.value = e.message ?: e.javaClass.simpleName
            } finally {
                busy.value = false
                sessions.value = dao.sessions()
            }
        }
    }

    private suspend fun persist(role: String, text: String) {
        val id = sessionId
        dao.insertMessage(Message(sessionId = id, role = role, content = text, createdAt = System.currentTimeMillis()))
        val title = messages.value.firstOrNull { it.role == "user" }?.text?.take(40) ?: "Sesi baru"
        dao.touchSession(id, title, System.currentTimeMillis())
    }

    private fun appendSystem(text: String) = viewModelScope.launch {
        messages.value = messages.value + UiMessage("assistant", text)
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
            "/compact" -> compact()
            else -> appendSystem("Command tidak dikenal: $name\n\n$HELP")
        }
    }

    private fun compact() = viewModelScope.launch {
        val current = messages.value
        if (current.size < 2) {
            appendSystem("Riwayat terlalu pendek untuk diringkas.")
            return@launch
        }
        busy.value = true
        error.value = null
        try {
            val summary = withContext(Dispatchers.IO) {
                LlmClient.chat(
                    Settings.config.value,
                    current + UiMessage(
                        "user",
                        "Ringkas percakapan di atas jadi catatan kerja padat: keputusan, kode/nama file " +
                            "yang disepakati, dan langkah berikutnya. Bahasa Indonesia, maksimal 20 baris.",
                    ),
                    sysPrompt,
                )
            }
            dao.clearMessages(sessionId)
            val note = "[compact]\n$summary"
            dao.insertMessage(Message(sessionId = sessionId, role = "assistant", content = note, createdAt = System.currentTimeMillis()))
            messages.value = listOf(UiMessage("assistant", note))
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
        """.trimIndent()

        private const val SYSTEM_PROMPT =
            "Kamu adalah NovaAgent, coding agent di Android. Jawab ringkas, tunjukkan kode konkret."
    }
}
