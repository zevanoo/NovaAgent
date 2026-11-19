package com.novaagent.app.shell

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Shell without Termux and without Termux:API.
 *
 * Android 10+ only lets an app execute binaries that live in its extracted
 * native-library directory, so a Termux busybox ships as
 * `jniLibs/arm64-v8a/libbusybox-bin.so` together with its `libbusybox.so`,
 * `libselinux.so` and `libpcre2-8.so` deps, with the DT_NEEDED strings rewritten
 * to plain sonames. The runner applets (`sh`, `ls`, `grep`, ...) are busybox
 * argv[0] dispatch, so one binary covers the whole userland.
 *
 * ponytail: busybox userland, not a Linux distro. Anything needing apt or a real
 * glibc toolchain still needs Termux; such a command now fails with a clear
 * message instead of a silent "command not found".
 */
object LocalShell {

    private const val BIN = "libbusybox-bin.so"

    /** Absolute path of the shipped busybox, or null when this ABI has none. */
    fun binPath(context: Context): String? =
        File(context.applicationInfo.nativeLibraryDir, BIN).takeIf { it.isFile }?.absolutePath

    fun available(context: Context): Boolean = binPath(context) != null

    /** Per-app home so the shell never needs a storage permission. */
    fun home(context: Context): File = File(context.filesDir, "home").apply { mkdirs() }

    fun run(context: Context, command: String, timeoutMs: Int = 120_000, cwd: String = ""): Result {
        val bin = binPath(context)
            ?: return Result(null, "", "Shell bawaan tidak tersedia untuk perangkat ini.")
        val tmp = File(context.filesDir, "tmp/" + UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        val out = File(tmp, "out.txt")
        val err = File(tmp, "err.txt")
        val script = File(tmp, "cmd.sh").apply { writeText(command) }
        val home = home(context).absolutePath
        val workIn = listOf(cwd, home).firstOrNull { it.isNotBlank() && File(it).isDirectory } ?: home

        return try {
            val pb = ProcessBuilder(bin, "sh", script.absolutePath)
                .redirectOutput(out)
                .redirectError(err)
            pb.directory(File(workIn))
            pb.environment().apply {
                put("HOME", home)
                put("TMPDIR", tmp.absolutePath)
                put("PATH", "$workIn/bin:/system/bin:/system/xbin")
                put("PWD", workIn)
                put("TERM", "dumb")
                // The linker only searches these for the busybox deps.
                put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
            }

            val proc = pb.start()
            if (!proc.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                return Result(null, out.readText(), "Timeout ${timeoutMs / 1000}s.")
            }
            Result(proc.exitValue(), out.readText().trimEnd(), err.readText().trimEnd())
        } catch (e: Exception) {
            Result(null, "", e.message ?: e.javaClass.simpleName)
        } finally {
            tmp.deleteRecursively()
        }
    }

    data class Result(val exitCode: Int?, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0

        /** Merged output, the shape the model expects. */
        val output: String
            get() = when {
                stderr.isBlank() -> stdout
                stdout.isBlank() -> stderr
                else -> stdout + "\n" + stderr
            }
    }
}
