package com.novaagent.app.bridge

import android.content.Context
import android.content.Intent
import android.os.Environment
import java.io.File
import java.util.UUID

/**
 * Talks to Termux through `com.termux.RUN_COMMAND`.
 *
 * RUN_COMMAND is fire-and-forget: the intent carries no result back. So every
 * command is wrapped to redirect its output into a scratch directory the app
 * can read, plus a `code.txt` marker the poller waits for.
 *
 * Requires, on the Termux side:
 *   - Termux + Termux:API installed
 *   - `allow-external-apps=true` in ~/.termux/termux.properties, Termux restarted
 *   - storage permission (to write into /sdcard)
 * and on this side: MANAGE_EXTERNAL_STORAGE granted, plus the
 * `com.termux.permission.RUN_COMMAND` permission in the manifest.
 */
object TermuxBridge {

    private const val TERMUX = "com.termux"
    private const val TERMUX_API = "com.termux.api"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"

    data class Outcome(val exitCode: Int?, val output: String, val error: String? = null) {
        val ok: Boolean get() = error == null && exitCode == 0
    }

    fun exchangeRoot(): File = File(Environment.getExternalStorageDirectory(), "novaagent/run")

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun status(context: Context): String {
        val termux = isInstalled(context, TERMUX)
        val api = isInstalled(context, TERMUX_API)
        val storage = Environment.isExternalStorageManager()
        return buildString {
            appendLine(if (termux) "Termux: terpasang" else "Termux: TIDAK ADA")
            appendLine(if (api) "Termux:API: terpasang" else "Termux:API: TIDAK ADA")
            appendLine(if (storage) "Akses storage: penuh" else "Akses storage: belum (perlu izin)")
            append("Tukar file: " + exchangeRoot().absolutePath)
        }
    }

    fun run(context: Context, command: String, timeoutMs: Int = 120_000): Outcome {
        if (!isInstalled(context, TERMUX)) return Outcome(null, "", "Termux belum terpasang.")
        if (!Environment.isExternalStorageManager()) {
            return Outcome(null, "", "Izin akses storage belum diberikan (Settings → izin file).")
        }

        val id = UUID.randomUUID().toString().take(8)
        val dir = File(exchangeRoot(), id)
        if (!dir.mkdirs()) return Outcome(null, "", "Tidak bisa membuat folder tukar file: " + dir)
        val out = File(dir, "out.txt")
        val code = File(dir, "code.txt")

        val script = """
            {
            $command
            } > ${quote(out.absolutePath)} 2>&1
            echo ${'$'}? > ${quote(code.absolutePath)}
        """.trimIndent()

        val intent = Intent(ACTION).apply {
            setClassName(TERMUX, SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }

        val sent = try {
            context.startService(intent)
            true
        } catch (e: Exception) {
            false
        }
        if (!sent) {
            dir.deleteRecursively()
            return Outcome(
                null, "",
                "Termux menolak perintah. Set `allow-external-apps=true` di " +
                    "~/.termux/termux.properties lalu restart Termux.",
            )
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (code.isFile && code.length() > 0) {
                val text = out.takeIf { it.isFile }?.readText().orEmpty()
                val exit = code.readText().trim().toIntOrNull()
                dir.deleteRecursively()
                return Outcome(exit, text.trim())
            }
            Thread.sleep(150)
        }

        dir.deleteRecursively()
        return Outcome(null, "", "Timeout ${timeoutMs / 1000}s — Termux tidak menyelesaikan perintah.")
    }

    private fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"
}
