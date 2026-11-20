package com.novaagent.app.data

import android.content.Context

/**
 * Working directory resolution.
 *
 * One setting, [defaultDir]: the root new sessions get a folder under. Default is
 * `/sdcard/Android/data/<pkg>/files/projects`, the app's own external dir — the
 * agent's sandbox. Nothing outside it is reachable from a tool.
 *
 * Each session gets `<root>/<title slug>`; the agent creates it lazily on the
 * first write, so read-only chats do not litter the disk.
 */
object Workspace {
    private const val P_DEFAULT = "workdirDefault"
    private const val P_SESSION_DIR = "sessionDirs" // "id=path" lines

    fun defaultDir(context: Context): String {
        val stored = prefs(context).getString(P_DEFAULT, "").orEmpty().trim()
        // A stored path from an older build may point outside the sandbox now;
        // fall back rather than let the agent write elsewhere.
        return if (stored.isNotBlank() && AppFiles.isInsideSandbox(context, stored)) stored
        else AppFiles.projects(context).absolutePath
    }

    /** Returns false and changes nothing when [path] is outside the sandbox. */
    fun setDefaultDir(context: Context, path: String): Boolean {
        val clean = path.trim()
        if (!AppFiles.isInsideSandbox(context, clean)) return false
        prefs(context).edit().putString(P_DEFAULT, clean).apply()
        return true
    }

    /** Folder of one session, or blank until the agent creates it. */
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

    fun forgetSession(context: Context, sessionId: Long) = setSessionDir(context, sessionId, "")

    /** Directory a command should run in. */
    fun resolve(context: Context, sessionId: Long): String =
        sessionDir(context, sessionId).ifBlank { defaultDir(context) }

    /** Where a session folder goes, without creating it. */
    fun dirFor(context: Context, sessionId: Long, title: String): String {
        val base = defaultDir(context).trimEnd('/')
        val slug = slugOf(title).ifBlank { "sesi-$sessionId" }
        return "$base/$slug"
    }

    /** One-line label for the chat header. */
    fun display(context: Context, sessionId: Long): String = resolve(context, sessionId)

    /** Lowercase, dash-separated, ascii: safe to interpolate into a shell command. */
    fun slugOf(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)

    private fun prefs(context: Context) = context.getSharedPreferences("novaagent", 0)
}
