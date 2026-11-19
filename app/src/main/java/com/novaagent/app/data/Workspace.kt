package com.novaagent.app.data

import android.content.Context

/** Shell working directory for tool calls, shared between chat and settings screens. */
object Workspace {
    private const val KEY = "workdir"

    fun get(context: Context): String =
        context.getSharedPreferences("novaagent", 0).getString(KEY, "").orEmpty()

    fun set(context: Context, path: String) {
        context.getSharedPreferences("novaagent", 0).edit().putString(KEY, path.trim()).apply()
    }

    fun display(context: Context): String = get(context).ifBlank { "(HOME Termux)" }
}
