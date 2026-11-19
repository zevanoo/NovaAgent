package com.novaagent.app.data

import android.content.Context

/**
 * Working directory resolution, most specific wins:
 *
 *   session workdir (set per chat from the session drawer)
 *     > project workdir (Settings, the folder you usually work in)
 *       > main workdir (Settings default / Termux $HOME)
 *
 * `resolve` returns the first non-blank, so a session can be pinned to another
 * folder without losing the project default.
 */
object Workspace {
    private const val P_MAIN = "workdirMain"
    private const val P_PROJECT = "workdirProject"
    private const val P_SESSION = "workdirSession"

    fun main(context: Context): String = prefs(context).getString(P_MAIN, "").orEmpty()

    fun project(context: Context): String = prefs(context).getString(P_PROJECT, "").orEmpty()

    fun session(context: Context): String = prefs(context).getString(P_SESSION, "").orEmpty()

    fun setMain(context: Context, path: String) = put(context, P_MAIN, path)

    fun setProject(context: Context, path: String) = put(context, P_PROJECT, path)

    fun setSession(context: Context, path: String) = put(context, P_SESSION, path)

    fun resolve(context: Context): String =
        listOf(session(context), project(context), main(context))
            .firstOrNull { it.isNotBlank() }.orEmpty()

    /** Which tier produced the current value — shown in the UI so the source is obvious. */
    fun source(context: Context): String = when {
        session(context).isNotBlank() -> "sesi"
        project(context).isNotBlank() -> "project"
        main(context).isNotBlank() -> "main"
        else -> "HOME Termux"
    }

    fun display(context: Context): String = resolve(context).ifBlank { "(HOME Termux)" }

    /** Known project folders, for the session dropdown. Newest first, de-duplicated. */
    fun known(context: Context): List<String> {
        val raw = prefs(context).getString("workdirKnown", "").orEmpty()
        return raw.split('\n').map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    /** Records a folder so it shows up in the session picker next time. */
    fun remember(context: Context, path: String) {
        val p = path.trim()
        if (p.isBlank()) return
        val list = (listOf(p) + known(context)).distinct().take(12)
        prefs(context).edit().putString("workdirKnown", list.joinToString("\n")).apply()
    }

    private fun put(context: Context, key: String, path: String) {
        val p = path.trim()
        prefs(context).edit().putString(key, p).apply()
        remember(context, p)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("novaagent", 0)
}
