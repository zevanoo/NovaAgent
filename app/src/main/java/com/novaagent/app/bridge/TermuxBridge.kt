package com.novaagent.app.bridge

import android.content.Context
import com.novaagent.app.shell.LocalShell

/**
 * Command execution.
 *
 * The only path is [LocalShell]: a busybox shipped inside the APK. No external
 * app is involved, so installing or opening NovaAgent can never break Termux and
 * nothing has to be configured in Termux first.
 */
object TermuxBridge {

    private const val TERMUX = "com.termux"

    data class Outcome(val exitCode: Int?, val output: String, val error: String? = null) {
        val ok: Boolean get() = error == null && exitCode == 0
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun status(context: Context): String = buildString {
        appendLine(if (LocalShell.available(context)) "Shell bawaan: tersedia" else "Shell bawaan: TIDAK ADA")
        appendLine("Home: " + LocalShell.home(context).absolutePath)
        append(
            if (isInstalled(context, TERMUX)) "Termux: terpasang (tidak dipakai lagi)"
            else "Termux: tidak dipasang, dan tidak diperlukan"
        )
    }

    fun run(context: Context, command: String, timeoutMs: Int = 120_000, cwd: String = ""): Outcome {
        val r = LocalShell.run(context, command, timeoutMs, cwd)
        return Outcome(r.exitCode, r.output, if (r.exitCode == null && r.stderr.isNotEmpty()) r.stderr else null)
    }
}
