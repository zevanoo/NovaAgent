package com.novaagent.app.data

import android.content.Context

/**
 * HARD ceiling on tool rounds inside one user turn.
 *
 * There is deliberately no wall-clock watchdog on commands: long builds and
 * installs are legitimate. The loop instead stops after `maxRounds` tool rounds
 * and the service asks the user what to do next (continue or new prompt), so a
 * runaway agent costs one notification rather than a killed long command.
 */
object Limits {
    private const val KEY_MEM = "memoryCapMb"
    private const val KEY_ROUNDS = "maxRounds"
    private const val KEY_WRITE_POLICY = "memoryWritePolicy"

    const val DEFAULT_ROUNDS = 20
    val ROUND_OPTIONS = listOf(5, 10, 20, 40, 60)

    /** Auto: the agent appends to memory itself. Confirm: every write waits for the user. */
    const val POLICY_AUTO = "auto"
    const val POLICY_CONFIRM = "confirm"

    fun memoryCapMb(context: Context): Int = prefs(context).getInt(KEY_MEM, 0)

    fun setMemoryCapMb(context: Context, mb: Int) {
        prefs(context).edit().putInt(KEY_MEM, mb.coerceIn(0, 4096)).apply()
    }

    fun maxRounds(context: Context): Int = prefs(context).getInt(KEY_ROUNDS, DEFAULT_ROUNDS).coerceIn(1, 200)

    fun setMaxRounds(context: Context, rounds: Int) {
        prefs(context).edit().putInt(KEY_ROUNDS, rounds.coerceIn(1, 200)).apply()
    }

    /** Default confirm: memory shapes every later prompt, so silent writes deserve a tap. */
    fun memoryWritePolicy(context: Context): String =
        prefs(context).getString(KEY_WRITE_POLICY, POLICY_CONFIRM) ?: POLICY_CONFIRM

    fun setMemoryWritePolicy(context: Context, policy: String) {
        prefs(context).edit()
            .putString(KEY_WRITE_POLICY, if (policy == POLICY_AUTO) POLICY_AUTO else POLICY_CONFIRM)
            .apply()
    }

    fun isAutoWrite(context: Context): Boolean = memoryWritePolicy(context) == POLICY_AUTO

    fun describeMemory(context: Context): String {
        val mb = memoryCapMb(context)
        return if (mb == 0) "tanpa batas" else "$mb MB"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("novaagent", 0)
}
