package com.novaagent.app.data

import android.content.Context
import java.io.File

/**
 * Two-tier memory.
 *
 * Global (app filesDir/memory/global): SOUL.md persona + USER.md personalization.
 * Loaded into every session regardless of project.
 *
 * Project (app filesDir/memory/projects/<slug>/MEMORY.md): deep context for one
 * working directory. Loaded only when the session's workdir resolves into it, so
 * the model does not carry unrelated project detail in every prompt.
 */
object MemoryFiles {
    const val SOUL = "SOUL.md"
    const val USER = "USER.md"
    const val PROJECT = "MEMORY.md"

    private const val PROMPT_BUDGET = 6_000

    fun globalDir(context: Context): File = File(context.filesDir, "memory/global").apply { mkdirs() }

    fun projectsDir(context: Context): File = File(context.filesDir, "memory/projects").apply { mkdirs() }

    fun projectDir(context: Context, slug: String): File = File(projectsDir(context), slug).apply { mkdirs() }

    private fun globalFile(context: Context, name: String) = File(globalDir(context), name)

    private fun projectFile(context: Context, slug: String, name: String) = File(projectDir(context, slug), name)

    /** Directory path -> stable slug. Flat, so `~/proj/a` and `~/proj-a` cannot collide. */
    fun slugOf(workdir: String): String {
        val normalized = workdir.trim().trimEnd('/')
        if (normalized.isBlank()) return "default"
        return normalized.removePrefix("/").replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    }

    /** Project name shown to the user: the folder's own name. */
    fun projectName(workdir: String): String =
        workdir.trim().trimEnd('/').substringAfterLast('/').ifBlank { workdir }

    /** Creates the global files once, so /memory and Settings have something to show and edit. */
    fun ensureGlobal(context: Context) {
        writeIfMissing(globalFile(context, SOUL), DEFAULT_SOUL)
        writeIfMissing(globalFile(context, USER), DEFAULT_USER)
    }

    fun ensureProject(context: Context, workdir: String) {
        if (workdir.isBlank()) return
        val slug = slugOf(workdir)
        writeIfMissing(projectFile(context, slug, PROJECT), projectTemplate(workdir))
    }

    private fun projectTemplate(workdir: String) = """
        # MEMORY.md — ${projectName(workdir)}

        Path: `$workdir`

        ## Status
        (belum ada catatan)

        ## Keputusan

        ## Log
    """.trimIndent()

    private fun writeIfMissing(file: File, text: String) {
        if (!file.isFile) file.writeText(text)
    }

    /**
     * Builds the system prompt. SOUL + USER are global, always loaded. MEMORY.md of
     * the active project is loaded only when the resolved workdir maps to a project
     * directory, so one project's deep context does not leak into another's session.
     */
    fun systemPrompt(context: Context, workdir: String): Prompt {
        ensureGlobal(context)
        val parts = StringBuilder()
        val soul = read(globalFile(context, SOUL)).orEmpty().ifBlank { DEFAULT_SOUL }
        parts.append(soul.trim()).append("\n\n")

        read(globalFile(context, USER))?.takeIf { it.isNotBlank() }?.let {
            parts.append("## Tentang user\n").append(trim(it)).append("\n\n")
        }

        val slug = if (workdir.isBlank()) null else slugOf(workdir)
        if (workdir.isNotBlank()) ensureProject(context, workdir)
        val projectNote = slug?.let { s -> projectFile(context, s, PROJECT) }
            ?.takeIf { it.isFile }?.let { read(it) }?.takeIf { it.isNotBlank() }

        if (projectNote != null) {
            parts.append("## Konteks project (${projectName(workdir)})\n").append(trim(projectNote)).append("\n")
        }
        return Prompt(text = parts.toString().trim(), projectSlug = slug, hasProjectNote = projectNote != null)
    }

    data class Prompt(val text: String, val projectSlug: String?, val hasProjectNote: Boolean)

    /** Status for /memory: what exists, where, and how big. */
    fun status(context: Context, workdir: String): String {
        ensureGlobal(context)
        val lines = mutableListOf<String>()
        listOf(SOUL, USER).forEach { name ->
            val f = globalFile(context, name)
            lines += "• global/${name} — ${if (f.isFile) "${f.readText().length} karakter" else "belum ada"}"
        }
        val slug = slugOf(workdir)
        val pf = projectFile(context, slug, PROJECT)
        lines += if (workdir.isBlank()) {
            "• MEMORY.md project: (workdir belum diset — memory project tidak dimuat)"
        } else if (pf.isFile) {
            "• project ${projectName(workdir)}/MEMORY.md — ${pf.readText().length} karakter"
        } else {
            "• project ${projectName(workdir)}/MEMORY.md — belum ada (dibuat saat dipakai)"
        }
        lines += "Global: ${globalDir(context).absolutePath}"
        lines += "Projects: ${projectsDir(context).absolutePath}"
        lines += "Tulis /memory <soul|user|project> <teks>, atau /memory edit <soul|user|project>"
        return lines.joinToString("\n")
    }

    fun read(context: Context, name: String): String? = read(globalFile(context, name))

    private fun read(file: File): String? = file.takeIf { it.isFile }?.readText()

    /** Reads a whole memory file for the in-app editor. Unknown target returns null. */
    fun readTarget(context: Context, workdir: String, target: String): Pair<File, String>? {
        ensureGlobal(context)
        val file = fileFor(context, workdir, target) ?: return null
        return file to (read(file).orEmpty())
    }

    private fun fileFor(context: Context, workdir: String, target: String): File? {
        return when (target.uppercase()) {
            "SOUL" -> globalFile(context, SOUL)
            "USER" -> globalFile(context, USER)
            "PROJECT", "MEMORY" -> {
                if (workdir.isBlank()) return null
                ensureProject(context, workdir)
                projectFile(context, slugOf(workdir), PROJECT)
            }
            else -> null
        }
    }

    /** Overwrites a memory file from the editor. Refuses a blank write to avoid wiping memory by accident. */
    fun writeTarget(context: Context, workdir: String, target: String, text: String): String {
        val file = fileFor(context, workdir, target) ?: return "Error: target harus SOUL, USER, atau PROJECT."
        if (text.isBlank()) return "Error: isi kosong — tidak ditulis (mencegah memory terhapus)."
        file.parentFile?.mkdirs()
        file.writeText(text)
        return "OK: ${file.name} disimpan (${text.length} karakter)."
    }

    /** Appends to a memory file, creating it if needed. Used by /memory save and the write policy. */
    fun append(context: Context, workdir: String, target: String, text: String): String {
        val file = fileFor(context, workdir, target) ?: return "Error: target harus SOUL, USER, atau PROJECT."
        file.parentFile?.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        file.appendText("\n- [$stamp] ${text.trim()}\n")
        return "OK: ditambahkan ke ${file.path}"
    }

    // ---- Pending writes (confirm policy) -------------------------------------

    private const val PENDING = "pending"

    /** Queues an agent memory write that still needs the user's approval. */
    fun queuePending(context: Context, workdir: String, target: String, note: String) {
        prefs(context).edit()
            .putString("$PENDING/$target", listOf(workdir, note).joinToString("\u0001"))
            .apply()
    }

    data class Pending(val target: String, val workdir: String, val note: String)

    fun pending(context: Context): List<Pending> =
        listOf("SOUL", "USER", "PROJECT").mapNotNull { target ->
            val raw = prefs(context).getString("$PENDING/$target", null) ?: return@mapNotNull null
            val parts = raw.split('\u0001')
            if (parts.size < 2) null else Pending(target, parts[0], parts[1])
        }

    /** Applies one queued write and clears it. */
    fun confirmPending(context: Context, target: String): String {
        val item = pending(context).firstOrNull { it.target == target }
            ?: return "Tidak ada catatan tertunda untuk $target."
        val result = append(context, item.workdir, item.target, item.note)
        prefs(context).edit().remove("$PENDING/$target").apply()
        return result
    }

    fun rejectPending(context: Context, target: String) {
        prefs(context).edit().remove("$PENDING/$target").apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("novaagent", 0)

    private fun trim(text: String): String =
        if (text.length <= PROMPT_BUDGET / 3) text.trim()
        else text.trim().take(PROMPT_BUDGET / 3) + "\n…(dipotong, lihat file lengkap)"

    private val DEFAULT_SOUL = """
        # SOUL.md — persona

        Kamu adalah NovaAgent, coding agent yang berjalan di Android lewat Termux.
        Jawab ringkas, tunjukkan kode konkret, dan verifikasi dengan tool sebelum
        mengklaim sesuatu berhasil.
    """.trimIndent()

    private val DEFAULT_USER = """
        # USER.md — personalisasi

        (tambahkan preferensi user di sini; file ini dimuat di semua project)
    """.trimIndent()
}
