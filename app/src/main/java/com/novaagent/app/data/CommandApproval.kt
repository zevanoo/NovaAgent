package com.novaagent.app.data

import kotlinx.coroutines.flow.MutableStateFlow

enum class CommandRisk(val label: String) { SAFE("aman"), NEUTRAL("netral"), RISKY("beresiko"), DANGEROUS("bahaya"), EXTREME("sangat bahaya") }
data class ApprovalRequest(val command: String, val risk: CommandRisk, val createdAt: Long = System.currentTimeMillis())

/** Conservative shell risk gate. Unknown commands stay neutral; destructive patterns escalate. */
object CommandApproval {
    val pending = MutableStateFlow<ApprovalRequest?>(null)
    private val lock = Object()
    private var answer: String? = null
    private val always = mutableSetOf<String>()
    private val session = mutableSetOf<String>()

    fun classify(command: String): CommandRisk {
        val c = command.lowercase()
        if (Regex("(^|[;&|])\\s*(rm|dd|mkfs|format|reboot|shutdown)\\b|:\\(\\)\\s*\\{").containsMatchIn(c)) return CommandRisk.EXTREME
        if (Regex("\\b(sudo|su|chmod\\s+777|chown|kill|pkill|git\\s+(reset|clean)|npm\\s+publish|curl.*\\|.*sh)\\b").containsMatchIn(c)) return CommandRisk.DANGEROUS
        if (Regex("\\b(apt|pkg|pip|npm|gradle|git\\s+(push|checkout)|mv|cp|mkdir|touch|tee|sed|python|node)\\b").containsMatchIn(c)) return CommandRisk.RISKY
        if (Regex("\\b(ls|pwd|whoami|id|which|cat|head|tail|find|grep|echo|printf|date|git\\s+(status|diff|log))\\b").containsMatchIn(c)) return CommandRisk.SAFE
        return CommandRisk.NEUTRAL
    }

    fun approve(command: String, choice: String) {
        val key = command.trim()
        if (choice == "session") session += key
        if (choice == "always") always += key
        synchronized(lock) { answer = choice; lock.notifyAll() }
        pending.value = null
    }

    fun rejectOnTimeout() {
        synchronized(lock) { answer = "reject"; lock.notifyAll() }
        pending.value = null
    }

    fun await(command: String): Boolean {
        val risk = classify(command)
        if (risk == CommandRisk.SAFE || session.contains(command.trim()) || always.contains(command.trim())) return true
        pending.value = ApprovalRequest(command, risk)
        val deadline = System.currentTimeMillis() + 300_000L
        synchronized(lock) {
            answer = null
            while (answer == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                lock.wait(remaining)
            }
        }
        pending.value = null
        return answer == "once" || answer == "session" || answer == "always"
    }
}
