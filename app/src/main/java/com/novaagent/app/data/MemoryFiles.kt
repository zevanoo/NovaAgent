package com.novaagent.app.data

import android.content.Context
import java.io.File

/**
 * Global vs per-project memory. M1 only reads what exists and reports it;
 * auto-create and write policy land in M4.
 */
object MemoryFiles {
    const val SOUL = "SOUL.md"
    const val USER = "USER.md"
    const val PROJECT = "MEMORY.md"

    fun dir(context: Context): File =
        File(context.filesDir, "memory").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun status(context: Context): String {
        val d = dir(context)
        val present = d.listFiles()?.filter { it.name.endsWith(".md") }.orEmpty()
        if (present.isEmpty()) {
            return "Belum ada file memory. Akan dibuat otomatis di M4.\nLokasi: " + d.absolutePath
        }
        return present.sortedBy { it.name }.joinToString("\n") {
            "• ${it.name} — ${it.readText().length} karakter"
        } + "\nLokasi: " + d.absolutePath
    }

    fun read(context: Context, name: String): String? =
        file(context, name).takeIf { it.isFile }?.readText()
}
