package com.novaagent.app.bridge

import android.content.Context
import android.content.Intent
import java.io.File
import java.util.UUID

/**
 * Runs commands in the real Termux app through `com.termux.RUN_COMMAND`.
 *
 * Termux:API is NOT involved — that app only exposes sensor/notification
 * endpoints to shell scripts. `RUN_COMMAND` lives in Termux itself, so Termux
 * alone is enough. One-time setup on the Termux side:
 *
 *     echo "allow-external-apps=true" >> ~/.termux/termux.properties
 *
 * then restart Termux. On this side the manifest declares
 * `com.termux.permission.RUN_COMMAND`.
 *
 * The intent is fire-and-forget: nothing comes back to the caller. So every
 * command is wrapped to write stdout+stderr into a per-call scratch dir inside
 * this app's own files dir, plus a `code.txt` the caller polls for.
 */
object TermuxBridge {

    private const val TERMUX = "com.termux"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"

    data class Outcome(val exitCode: Int?, val output: String, val error: String? = null) {
        val ok: Boolean get() = error == null && exitCode == 0
    }

    /** Scratch dir for one call. App-private, so Termux needs no storage grant. */
    fun exchangeRoot(context: Context): File =
        File(context.filesDir, "termux-run").apply { mkdirs() }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun status(context: Context): String = buildString {
        appendLine(if (isInstalled(context)) "Termux: terpasang" else "Termux: TIDAK ADA")
        appendLine("Tukar output: " + exchangeRoot(context).absolutePath)
        append(
            "Sekali saja di Termux: tambah allow-external-apps=true di " +
                "~/.termux/termux.properties lalu restart Termux."
        )
    }

    fun run(context: Context, command: String, timeoutMs: Int = 120_000, cwd: String = ""): Outcome {
        if (!isInstalled(context)) {
            return Outcome(null, "", "Termux belum terpasang. Pasang Termux (F-Droid) untuk menjalankan perintah.")
        }

        val dir = File(exchangeRoot(context), UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        if (!dir.isDirectory) return Outcome(null, "", "Tidak bisa membuat folder tukar: $dir")
        val out = File(dir, "out.txt")
        val code = File(dir, "code.txt")
        val workIn = cwd.takeIf { it.isNotBlank() } ?: HOME

        val script = """
            cd ${quote(workIn)} 2>/dev/null || cd $HOME
            {
            $command
            } > ${quote(out.absolutePath)} 2>&1
            echo ${'$'}? > ${quote(code.absolutePath)}
        """.trimIndent()

        val intent = Intent(ACTION).apply {
            setClassName(TERMUX, SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", workIn)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }

        val sent = runCatching { context.startService(intent) }.isSuccess
        if (!sent) {
            dir.deleteRecursively()
            return Outcome(
                null, "",
                "Termux menolak perintah. Set allow-external-apps=true di " +
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
        return Outcome(null, "", "Timeout ${timeoutMs / 1000}s — perintah tidak selesai.")
    }

    private fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"
}
