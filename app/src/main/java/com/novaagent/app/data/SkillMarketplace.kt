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
        var connection: HttpURLConnection? = null
        return try {
            val archive = File(staging, "skill.zip")
            val url = if (uri.contains("github.com/") && !uri.endsWith(".zip"))
                uri.trimEnd('/') + "/archive/refs/heads/main.zip" else uri
        val responseUrl = URL(url)
        if (responseUrl.protocol != "https" || responseUrl.host !in setOf("github.com", "codeload.github.com", "raw.githubusercontent.com")) {
            return "Error: URL download di luar host GitHub yang diizinkan."
        }
        connection = responseUrl.openConnection() as HttpURLConnection
        val conn = connection!!
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000; conn.readTimeout = 60_000
        var code = conn.responseCode
        if (code in 300..399) {
            val location = conn.getHeaderField("Location") ?: return "Error: redirect skill tanpa Location."
            val redirected = URL(responseUrl, location)
            if (redirected.protocol != "https" || redirected.host !in setOf("github.com", "codeload.github.com"))
                return "Error: redirect ke host di luar GitHub ditolak."
            conn.disconnect()
            connection = redirected.openConnection() as HttpURLConnection
            connection!!.connectTimeout = 15_000; connection!!.readTimeout = 60_000
            code = connection!!.responseCode
        }
        if (code !in 200..299) return "Error: download HTTP $code"
        val connFinal = connection!!
        val size = connFinal.contentLengthLong
        if (size > MAX_ARCHIVE_BYTES) return "Error: archive melewati batas 5 MB."
        var downloaded = 0L
        connFinal.inputStream.use { input -> archive.outputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                downloaded += n
                if (downloaded > MAX_ARCHIVE_BYTES) return "Error: archive melewati batas 5 MB."
                output.write(buffer, 0, n)
            }
        } }
            val extracted = File(staging, "unpacked").apply { mkdirs() }
            var count = 0
            var expanded = 0L
            ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > MAX_ENTRIES) return "Error: archive punya terlalu banyak file."
                    val out = File(extracted, entry.name).canonicalFile
                    if (!out.path.startsWith(extracted.canonicalPath + File.separator)) return "Error: archive path traversal."
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                expanded += n
                                if (expanded > MAX_EXPANDED_BYTES) return "Error: hasil ekstrak melewati batas 10 MB."
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }
            val skill = extracted.walkTopDown().firstOrNull { it.isDirectory && File(it, "SKILL.md").isFile }
                ?: return "Error: archive tidak punya SKILL.md."
            val parsed = Skills.parse(File(skill, "SKILL.md").readText(), skill.name)
            if (parsed.body.length > 100_000) return "Error: SKILL.md terlalu besar."
            Skills.install(context, workdir, skill, overwrite)
        } catch (e: Exception) {
            "Error: skill gagal dipasang: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            connection?.disconnect()
            staging.deleteRecursively()
        }
    }

    private const val MAX_ARCHIVE_BYTES = 5L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 10L * 1024 * 1024
    private const val MAX_ENTRIES = 500
}
