package com.novaagent.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * Font sources.
 *
 * Bundled fonts live in `assets/fonts/{body,mono,heading}/`, so the APK always has
 * a usable default. The user can install their own .ttf/.otf into the same three
 * roles from Settings; a user font overrides the bundled one for that role.
 *
 * Asset fonts are declared as Compose Font resources (readable without a Context);
 * user fonts are built from a Typeface at runtime and cached per file+mtime,
 * because parsing a file on every recomposition would jank the message list.
 */
object AppFonts {
    const val ROLE_BODY = "body"
    const val ROLE_MONO = "mono"
    const val ROLE_HEADING = "heading"

    val roles = listOf(ROLE_BODY, ROLE_MONO, ROLE_HEADING)

    /** Bundled fallbacks. One file per role; other weights are synthesized. */
    private val bundledBody = FontFamily(Font("fonts/body/Inter-Regular.ttf", FontWeight.Normal))
    private val bundledMono = FontFamily(Font("fonts/mono/JetBrainsMono-Regular.ttf", FontWeight.Normal))
    private val bundledHeading = FontFamily(Font("fonts/heading/InterTight-Regular.ttf", FontWeight.Normal))

    private val userCache = HashMap<String, FontFamily?>()

    fun family(context: Context, role: String): FontFamily {
        val file = userFontFile(context, role)
        if (file != null) {
            val key = "$role:${file.name}:${file.lastModified()}"
            userCache[key]?.let { return it }
            val parsed = runCatching {
                Typeface.createFromFile(file)?.let { FontFamily(it) }
            }.getOrNull()
            // Cache failures too, so a corrupt file is not re-parsed every frame.
            userCache[key] = parsed
            if (parsed != null) return parsed
        }
        return when (role) {
            ROLE_MONO -> bundledMono
            ROLE_HEADING -> bundledHeading
            else -> bundledBody
        }
    }

    fun userDir(context: Context, role: String): File =
        File(context.filesDir, "fonts/$role").apply { mkdirs() }

    /** First font file installed for this role. */
    fun userFontFile(context: Context, role: String): File? =
        userDir(context, role).listFiles()
            ?.filter { it.isFile && it.name.lowercase().matches(Regex(".*\.(ttf|otf)")) }
            ?.minByOrNull { it.name }

    /** Copies a picked font into the role folder, replacing the previous one. */
    fun install(context: Context, role: String, uri: Uri): String {
        val name = displayName(context, uri).orEmpty()
        if (!name.lowercase().matches(Regex(".*\.(ttf|otf)"))) return "Error: file harus .ttf atau .otf"

        val dir = userDir(context, role)
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        val target = File(dir, name)
        val copyError = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } ?: error("tidak bisa membaca file")
        }.exceptionOrNull()
        if (copyError != null) return "Error: ${copyError.message ?: "gagal menyalin"}"

        // Prove the file parses before reporting success: a bad font would
        // otherwise silently fall back and look like nothing happened.
        if (runCatching { Typeface.createFromFile(target) }.getOrNull() == null) {
            target.delete()
            return "Error: file font tidak bisa dibaca Android."
        }
        userCache.clear()
        return "OK: font $role -> $name"
    }

    fun reset(context: Context, role: String): String {
        userDir(context, role).listFiles()?.forEach { it.delete() }
        userCache.clear()
        return "OK: font $role kembali ke bawaan."
    }

    /** Role -> installed file name, or "(bawaan)". */
    fun installed(context: Context): List<Pair<String, String>> =
        roles.map { it to (userFontFile(context, it)?.name ?: "(bawaan)") }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: uri.lastPathSegment
}
