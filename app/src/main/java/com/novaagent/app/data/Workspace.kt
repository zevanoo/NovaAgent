package com.novaagent.app.data

import android.content.Context

/**
 * Working directory resolution.
 *
 * Exactly ONE setting exists: [defaultDir] - the root the user picked. Each
 * session gets its own project folder underneath it, created by the agent the
 * first time it actually touches a file: `<defaultDir>/<title slug>`. A session
 * that only asks a read-only question stays on the default dir, so chats do not
 * litter the disk with empty folders.
 *
 * Precedence when a command runs: session project dir > default dir > Termux HOME.
 */
object Workspace {
    private const val P_DEFAULT = "workdirDefault"
    private const val P_SESSION_DIR = "sessionDirs" // "id=path" lines

    fun defaultDir(context: Context): String = prefs(context).getString(P_DEFAULT, "").orEmpty()

    fun setDefaultDir(context: Context, path: String) {
        prefs(context).edit().putString(P_DEFAULT, path.trim()).apply()
    }

    /** Project folder of one session, or blank when the agent has not created it yet. */
    fun sessionDir(context: Context, sessionId: Long): String {
        if (sessionId == 0L) return ""
        return prefs(context).getString(P_SESSION_DIR, "").orEmpty().lineSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == sessionId.toString() }
            ?.get(1)?.trim().orEmpty()
    }

    fun setSessionDir(context: Context, sessionId: Long, path: String) {
        if (sessionId == 0L) return
        val lines = prefs(context).getString(P_SESSION_DIR, "").orEmpty().lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("$sessionId=") }
            .toMutableList()
        if (path.isNotBlank()) lines.add("$sessionId=${path.trim()}")
        prefs(context).edit().putString(P_SESSION_DIR, lines.joinToString("\n")).apply()
    }

    /** Directory a command should run in. */
    fun resolve(context: Context, sessionId: Long): String =
        listOf(sessionDir(context, sessionId), defaultDir(context))
            .firstOrNull { it.isNotBlank() }.orEmpty()

    /** Where a new session's folder would go, without creating it. */
    fun projectDirFor(context: Context, sessionId: Long, title: String): String {
        val base = defaultDir(context).trim().trimEnd('/')
        if (base.isBlank()) return ""
        val slug = slugOf(title).ifBlank { "sesi-$sessionId" }
        return "$base/$slug"
    }

    /** One-line label for the chat header. */
    fun display(context: Context, sessionId: Long): String = when {
        sessionDir(context, sessionId).isNotBlank() -> sessionDir(context, sessionId)
        defaultDir(context).isNotBlank() -> defaultDir(context)
        else -> "HOME Termux"
    }

    /** Lowercase, dash-separated, ascii - safe to interpolate into a shell command. */
    fun slugOf(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)

    private fun prefs(context: Context) = context.getSharedPreferences("novaagent", 0)
}
