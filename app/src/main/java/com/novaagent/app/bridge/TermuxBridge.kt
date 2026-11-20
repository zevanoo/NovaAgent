package com.novaagent.app.bridge

import android.content.Context
import android.content.Intent
import com.novaagent.app.data.AppFiles
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

    /**
     * Scratch dir for one call.
     *
     * MUST be on shared storage: Termux runs as a different uid and Android's
     * SELinux policy blocks it from writing into the app's internal filesDir, so
     * an internal exchange dir made every command time out with no output at all.
     * `/sdcard/Android/data/<pkg>` is writable by both sides.
     */
    fun exchangeRoot(context: Context): File = AppFiles.exchange(context)

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
        // Always start inside the sandbox: a command must never inherit an
        // arbitrary cwd from the caller, and the app's home is the only place the
        // agent is allowed to work.
        val sandbox = AppFiles.root(context).absolutePath
        val workIn = cwd.takeIf { it.isNotBlank() && AppFiles.isInsideSandbox(context, it) }
            ?: sandbox

        // HOME is exported, not assumed: if Termux has never run, its shell has no
        // state and `set -e`-style failures would leave code.txt unwritten, which
        // the poller can only see as a timeout.
        val script = """
            export HOME=$HOME
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

        // First output write proves Termux actually picked the job up. If nothing
        // appears while no marker exists, the intent was silently refused (the
        // usual cause: allow-external-apps is off) - say so in a few seconds
        // instead of burning the whole timeout.
        val startMark = System.currentTimeMillis()
        var sawActivity = false

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!sawActivity && (out.exists() || code.exists())) sawActivity = true
            if (!sawActivity && System.currentTimeMillis() - startMark > START_GRACE_MS) {
                dir.deleteRecursively()
                return Outcome(
                    null, "",
                    "Termux tidak menjalankan perintah. Pastikan allow-external-apps=true " +
                        "di ~/.termux/termux.properties, lalu restart Termux.",
                )
            }
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

    /** How long to wait for Termux to touch the scratch dir before giving up. */
    private const val START_GRACE_MS = 8_000L

    private fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"
}
