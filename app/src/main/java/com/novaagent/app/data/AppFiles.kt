package com.novaagent.app.data

import android.content.Context
import java.io.File

/**
 * Where every piece of app data lives.
 *
 * One root, on shared storage:
 *
 *     /sdcard/Android/data/<pkg>/files/
 *         sessions/    <- shell working root ($HOME for commands)
 *         projects/    <- the agent's sandbox, one folder per session
 *         skills/
 *         memory/
 *         termux-run/  <- output exchange with Termux
 *         novaagent.db <- Room database
 *
 * `/sdcard/Android/data/<pkg>` is the app's external files dir: no permission
 * prompt, browsable from a file manager, deleted on uninstall together with the
 * app, and NOT readable by other apps. That last property is what makes it usable
 * as an agent sandbox — tools can read and write here and nowhere outside.
 *
 * Internal filesDir is the fallback when external storage is unavailable, so the
 * app still starts on a device with no mounted SD card.
 */
object AppFiles {

    private const val DIR_PROJECTS = "projects"
    private const val DIR_SESSIONS = "sessions"
    private const val DIR_SKILLS = "skills"
    private const val DIR_MEMORY = "memory"
    private const val DIR_EXCHANGE = "termux-run"
    const val DB_NAME = "novaagent.db"

    /** Root of all user-visible data. */
    fun root(context: Context): File =
        (context.getExternalFilesDir(null) ?: File(context.filesDir, "files")).apply { mkdirs() }

    /** The agent's sandbox: nothing outside this path is reachable from a tool. */
    fun projects(context: Context): File = File(root(context), DIR_PROJECTS).apply { mkdirs() }

    fun projectDir(context: Context, slug: String): File =
        File(projects(context), slug).apply { mkdirs() }

    /** Shell working root: commands start here, not at the filesystem root. */
    fun shellHome(context: Context): File = File(root(context), DIR_SESSIONS).apply { mkdirs() }

    fun skills(context: Context): File = File(root(context), DIR_SKILLS).apply { mkdirs() }

    fun memory(context: Context): File = File(root(context), DIR_MEMORY).apply { mkdirs() }

    fun exchange(context: Context): File = File(root(context), DIR_EXCHANGE).apply { mkdirs() }

    fun database(context: Context): File = File(root(context), DB_NAME)

    /**
     * True when [path] resolves inside the sandbox. Canonical paths are compared so
     * `..` and symlinks cannot climb out.
     */
    fun isInsideSandbox(context: Context, path: String): Boolean {
        val sandbox = runCatching { root(context).canonicalPath }.getOrElse { return false }
        val target = runCatching { File(path).canonicalPath }.getOrElse { return false }
        return target == sandbox || target.startsWith("$sandbox/")
    }

    /** One line for Settings: where the user can find everything. */
    fun describe(context: Context): String = root(context).absolutePath
}
