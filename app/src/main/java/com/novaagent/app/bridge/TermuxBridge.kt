package com.novaagent.app.bridge

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import com.novaagent.app.data.AppFiles
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs commands in the real Termux app through `com.termux.RUN_COMMAND`.
 *
 * Termux:API is NOT involved — that app only exposes sensor/notification
 * endpoints. `RUN_COMMAND` is part of Termux itself, and Termux publishes the
 * `com.termux.permission.RUN_COMMAND` permission that grants it.
 *
 * Three things must all hold, and Termux silently refuses if any is missing:
 *
 * 1. This app holds `com.termux.permission.RUN_COMMAND` (declared in the
 *    manifest; it is a normal permission, so it is granted at install).
 * 2. `allow-external-apps=true` in Termux's `~/.termux/termux.properties`.
 * 3. Termux is installed under the package name `com.termux`. A build from a
 *    different repo/ABI tag can carry a different id, and then nothing here can
 *    ever reach it.
 *
 * Result delivery uses EXTRA_PENDING_INTENT (Termux >= 0.109) instead of polling
 * a file. The file approach failed for two reasons that produced the same
 * invisible symptom — no output, then a timeout: Termux's uid could not create
 * the scratch dir, and a silently refused intent never wrote anything at all.
 * A pending intent makes Termux itself report why it refused.
 */
object TermuxBridge {

    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val EXTRA_RESULT_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val EXTRA_RESULT_BUNDLE = "result"
    private const val KEY_STDOUT = "stdout"
    private const val KEY_STDERR = "stderr"
    private const val KEY_EXIT = "exitCode"
    private const val KEY_ERR = "err"
    private const val KEY_ERRMSG = "errmsg"
    private const val ACTION_RESULT = "com.novaagent.app.TERMUX_RESULT"

    private val seq = AtomicInteger(1000)

    data class Outcome(val exitCode: Int?, val output: String, val error: String? = null) {
        val ok: Boolean get() = error == null && exitCode == 0
    }

    /** Scratch dir for result files, shared storage so both uids can write. */
    fun exchangeRoot(context: Context): File = AppFiles.exchange(context)

    /** Candidate Termux package ids, in the order we prefer them. */
    private val candidates = listOf("com.termux", "com.termux.dedug")

    /** The installed Termux package id, or null when no candidate is present. */
    fun packageId(context: Context): String? = candidates.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    fun isInstalled(context: Context): Boolean = packageId(context) != null

    /** Version name of the installed Termux, for the Settings report. */
    private fun versionName(context: Context, pkg: String): String =
        runCatching {
            context.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
        }.getOrDefault("?")

    /** Human-readable readiness report, shown in Settings. */
    fun status(context: Context): String {
        val pkg = packageId(context)
        return buildString {
            when {
                pkg == null ->
                    append("Termux: TIDAK TERPASANG (cari id com.termux). Perintah tidak bisa jalan.")
                pkg != "com.termux" ->
                    append("Termux: ditemukan sebagai '$pkg'. NovaAgent hanya bisa memanggil " +
                        "com.termux, jadi pasang Termux dari F-Droid/GitHub resmi.")
                else ->
                    append("Termux: terpasang (v${versionName(context, pkg)}), izin RUN_COMMAND " +
                        "dideklarasikan.")
            }
            appendLine()
            append("Kalau perintah gagal: jalankan allow-external-apps=true di " +
                "~/.termux/termux.properties lalu restart Termux.")
            appendLine()
            append("Folder tukar: " + exchangeRoot(context).absolutePath)
        }
    }

    /**
     * Sends [command] to Termux and waits for its result.
     *
     * Blocking: call from a background dispatcher, never from the main thread.
     */
    fun run(context: Context, command: String, timeoutMs: Int = 120_000, cwd: String = ""): Outcome {
        val pkg = packageId(context)
            ?: return Outcome(null, "", "Termux belum terpasang. Pasang Termux dari F-Droid " +
                "atau GitHub (packages resmi, id com.termux).")

        val scratch = File(exchangeRoot(context), UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        val outFile = File(scratch, "out.txt")

        val sandbox = AppFiles.root(context).absolutePath
        val workIn = cwd.takeIf { it.isNotBlank() && AppFiles.isInsideSandbox(context, it) } ?: sandbox
        val script = """
            export HOME=${shellQuote(AppFiles.shellHome(context).absolutePath)}
            cd ${shellQuote(workIn)} 2>/dev/null || cd ${'$'}HOME
            { $command ; } 2>&1 | tee ${shellQuote(outFile.absolutePath)}
            exit ${'$'}{PIPESTATUS[0]}
        """.trimIndent()

        val reqCode = seq.incrementAndGet()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Bundle? = null

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                result = intent?.getBundleExtra(EXTRA_RESULT_BUNDLE)
                latch.countDown()
            }
        }
        val filter = IntentFilter(ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        val pending = PendingIntent.getBroadcast(
            context, reqCode,
            Intent(ACTION_RESULT).setPackage(context.packageName),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
        )

        val intent = Intent(ACTION).apply {
            setClassName(pkg, SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/$pkg/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", workIn)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra(EXTRA_RESULT_PENDING_INTENT, pending)
        }

        try {
            val started = runCatching { context.startService(intent) }.getOrElse { e ->
                return Outcome(null, "", "Gagal memanggil Termux: ${e.message ?: e.javaClass.simpleName}")
            }
            if (started == null) {
                return Outcome(null, "", "Termux tidak menerima perintah (startService mengembalikan null).")
            }

            val finished = latch.await(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                return Outcome(null, "", "Timeout ${timeoutMs / 1000}s — perintah tidak selesai. " +
                    "Periksa notifikasi Termux.")
            }
            val b = result ?: return Outcome(null, "", "Termux tidak mengembalikan hasil.")
            val err = b.getInt(KEY_ERR, Activity.RESULT_OK)
            val errmsg = b.getString(KEY_ERRMSG).orEmpty()
            if (err != Activity.RESULT_OK) {
                return Outcome(null, b.getString(KEY_STDOUT).orEmpty(), explain(errmsg))
            }
            val stdout = b.getString(KEY_STDOUT).orEmpty()
            val stderr = b.getString(KEY_STDERR).orEmpty()
            val merged = (stdout + stderr).trim()
            return Outcome(b.getInt(KEY_EXIT, -1), merged.ifBlank {
                outFile.takeIf { it.isFile }?.readText().orEmpty().trim()
            })
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            scratch.deleteRecursively()
        }
    }

    /**
     * Turns Termux's generic failure message into the one thing the user has to
     * change. Termux reports a policy violation by name, so match on that rather
     * than guessing.
     */
    private fun explain(errmsg: String): String = when {
        errmsg.contains("allow-external-apps", ignoreCase = true) ||
            errmsg.contains("external apps", ignoreCase = true) ->
            "Termux menolak: allow-external-apps belum aktif. Jalankan " +
                "echo \"allow-external-apps=true\" >> ~/.termux/termux.properties " +
                "lalu restart Termux (matikan dari recents)."
        errmsg.contains("RUN_COMMAND", ignoreCase = true) ->
            "Termux menolak: izin RUN_COMMAND belum diberikan ke NovaAgent. " +
                "Buka Settings > Apps > NovaAgent > izin tambahan."
        errmsg.isBlank() -> "Termux menolak menjalankan perintah (tanpa pesan)."
        else -> "Termux menolak: $errmsg"
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
