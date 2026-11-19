package com.novaagent.app.data

import android.content.Context

/** Per-command resource limits, applied inside Termux. 0 means "no cap". */
object Limits {
    private const val KEY = "memoryCapMb"

    fun memoryCapMb(context: Context): Int =
        context.getSharedPreferences("novaagent", 0).getInt(KEY, 0)

    fun setMemoryCapMb(context: Context, mb: Int) {
        context.getSharedPreferences("novaagent", 0).edit().putInt(KEY, mb.coerceIn(0, 4096)).apply()
    }

    fun describe(context: Context): String {
        val mb = memoryCapMb(context)
        return if (mb == 0) "tanpa batas" else "$mb MB"
    }
}
