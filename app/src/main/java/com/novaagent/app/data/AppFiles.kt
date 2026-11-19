package com.novaagent.app.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where the app keeps things.
 *
 * Two roots only:
 * - [home] — the shell's working area, inside the app sandbox. No permission
 *   needed, wiped on uninstall.
 * - [media] — the same tree mirrored under `/sdcard/Android/media/<pkg>/`, which
 *   any file manager or Termux can read. Android creates that folder for us on
 *   install, so no storage permission is required to write own-files there.
 *
 * Sessions live under `<root>/projects/<slug>`; skills, memory and fonts stay in
 * the sandbox because they are app state, not user documents.
 */
object AppFiles {

    const val DIR_PROJECTS = "projects"

    fun home(context: Context): File = LocalShellHome.of(context)

    /** /sdcard/Android/media/<pkg> — shared, browsable, no permission needed. */
    fun media(context: Context): File =
        File(Environment.getExternalStorageDirectory(), "Android/media/" + context.packageName)

    fun projects(context: Context): File = File(home(context), DIR_PROJECTS).apply { mkdirs() }

    fun projectDir(context: Context, slug: String): File =
        File(projects(context), slug).apply { mkdirs() }

    /** True when [path] sits inside one of the two roots this app owns. */
    fun isOwned(context: Context, path: String): Boolean {
        val f = File(path).canonicalFile
        return listOf(home(context), media(context)).any { root ->
            val r = root.canonicalFile
            f.path == r.path || f.path.startsWith(r.path + "/")
        }
    }
}

/** Kept separate so [AppFiles] can be used from the shell package without a cycle. */
internal object LocalShellHome {
    fun of(context: Context): File = File(context.filesDir, "home").apply { mkdirs() }
}
