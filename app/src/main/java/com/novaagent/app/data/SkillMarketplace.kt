package com.novaagent.app.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Download and install a GitHub/archive skill after explicit user approval. */
object SkillMarketplace {
    fun install(context: Context, workdir: String, source: String, overwrite: Boolean = false): String {
        val uri = source.trim()
        if (!uri.startsWith("https://github.com/") && !uri.startsWith("https://raw.githubusercontent.com/")) {
            return "Error: sumber skill harus URL GitHub HTTPS."
        }
        val staging = File(context.cacheDir, "skill-download-${System.currentTimeMillis()}").apply { mkdirs() }
        return try {
            val archive = File(staging, "skill.zip")
            val url = if (uri.contains("github.com/") && !uri.endsWith(".zip"))
                uri.trimEnd('/') + "/archive/refs/heads/main.zip" else uri
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) return "Error: download HTTP ${conn.responseCode}"
            conn.inputStream.use { input -> archive.outputStream().use { input.copyTo(it) } }
            val extracted = File(staging, "unpacked").apply { mkdirs() }
            ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(extracted, entry.name).canonicalFile
                    if (!out.path.startsWith(extracted.canonicalPath + File.separator)) return "Error: archive path traversal."
                    if (entry.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            }
            val skill = extracted.walkTopDown().firstOrNull { it.isDirectory && File(it, "SKILL.md").isFile }
                ?: return "Error: archive tidak punya SKILL.md."
            val parsed = Skills.parse(File(skill, "SKILL.md").readText(), skill.name)
            if (parsed.body.length > 100_000) return "Error: SKILL.md terlalu besar."
            Skills.install(context, workdir, skill, overwrite)
        } catch (e: Exception) {
            "Error: skill gagal dipasang: ${e.message ?: e.javaClass.simpleName}"
        } finally { staging.deleteRecursively() }
    }
}
