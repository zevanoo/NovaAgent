package com.novaagent.app.data

import android.content.Context
import java.io.File

/**
 * Skill loader, M5.
 *
 * A skill is a folder with a SKILL.md: YAML frontmatter (name, description) plus
 * a markdown body. Skills are not injected into every prompt — the system prompt
 * only lists name + description so the model knows what exists, and the agent
 * loads a body with the `use_skill` tool when it decides the skill applies. That
 * keeps a 20-skill library from eating the context window.
 *
 * Three roots, first wins on name collision: project, user, bundled.
 */
object Skills {

    private const val FRONTMATTER = "---"

    fun roots(context: Context, workdir: String): List<File> = listOfNotNull(
        // project: skills shipped with the repo being worked on
        workdir.takeIf { it.isNotBlank() }?.let { File(it, ".novaagent/skills") },
        // user: installed by the user, survives project switches
        File(context.filesDir, "skills"),
        // bundled: ships inside the APK's asset-unpacked dir
        File(context.filesDir, "bundled-skills"),
    ).apply { forEach { it.mkdirs() } }

    data class Skill(
        val name: String,
        val description: String,
        val body: String,
        val dir: File,
    ) {
        /** One-line entry for the system prompt. */
        val indexLine: String get() = "- $name — $description"
    }

    /** All installed skills, deduplicated by name, project root winning. */
    fun all(context: Context, workdir: String): List<Skill> {
        val seen = LinkedHashMap<String, Skill>()
        roots(context, workdir).forEach { root ->
            root.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { dir ->
                    val md = File(dir, "SKILL.md").takeIf { it.isFile } ?: return@forEach
                    val parsed = parse(md.readText(), dir.name)
                    if (!seen.containsKey(parsed.name)) seen[parsed.name] = parsed.copy(dir = dir)
                }
        }
        return seen.values.toList()
    }

    /** Listing for /skill and the model. Empty when nothing is installed. */
    fun index(context: Context, workdir: String): String {
        val skills = all(context, workdir)
        if (skills.isEmpty()) return ""
        return skills.joinToString("\n") { it.indexLine }
    }

    /** Body of one skill, or an error string the model can read and recover from. */
    fun body(context: Context, workdir: String, name: String): String {
        val skill = all(context, workdir).firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return "Error: skill '$name' tidak terpasang. Tersedia: " +
                all(context, workdir).joinToString(", ") { it.name }.ifBlank { "(tidak ada)" }
        return "## Skill: ${skill.name}\n${skill.body}".trim()
    }

    /** Installs a skill folder: copies SKILL.md and any sibling files. Returns a status line. */
    fun install(context: Context, workdir: String, source: File, overwrite: Boolean): String {
        val name = source.name
        val dst = File(roots(context, workdir).let { if (it.size > 1) it[1] else it[0] }, name)
        if (dst.exists() && !overwrite) return "Skill '$name' sudah ada. Timpa atau batal?"
        source.copyRecursively(dst, overwrite = true)
        return "OK: skill '$name' dipasang di ${dst.path}"
    }

    fun remove(context: Context, workdir: String, name: String): String {
        val dst = roots(context, workdir).map { File(it, name) }.firstOrNull { it.exists() }
            ?: return "Skill '$name' tidak ditemukan."
        dst.deleteRecursively()
        return "OK: skill '$name' dihapus."
    }

    /**
     * Parses `--- key: value ---` frontmatter. Deliberately not a YAML parser:
     * skills only need name and description, and a dependency for two keys is
     * not worth it. Long/folded values fall back to the folder name.
     */
    fun parse(text: String, folderName: String): Skill {
        val lines = text.lines()
        var name = folderName
        var description = ""
        var bodyStart = 0

        if (lines.firstOrNull()?.trim() == FRONTMATTER) {
            val end = lines.drop(1).indexOfFirst { it.trim() == FRONTMATTER }
            if (end >= 0) {
                lines.subList(1, end + 1).forEach { line ->
                    val key = line.substringBefore(':', "").trim().lowercase()
                    val value = line.substringAfter(':', "").trim().trim('"', '\'')
                    when (key) {
                        "name" -> if (value.isNotBlank()) name = value
                        "description" -> if (value.isNotBlank()) description = value
                    }
                }
                bodyStart = end + 2
            }
        }

        val body = lines.drop(bodyStart).joinToString("\n").trim()
        if (description.isBlank()) {
            description = body.lineSequence()
                .map { it.trim().removePrefix("#").trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?.take(120)
                .orEmpty()
        }
        return Skill(name, description, body, File(folderName))
    }
}
