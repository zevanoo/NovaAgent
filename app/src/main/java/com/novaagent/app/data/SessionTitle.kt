package com.novaagent.app.data

/**
 * Title rules for a session.
 *
 * A session is not created until the first prompt is sent: the app opens on a
 * draft screen that merely looks like one. That keeps the session list clean and
 * means a session's title can always be derived from the prompt that created it.
 */
object SessionTitle {

    /** Placeholder title an old database may still contain. */
    const val NEW = "Sesi baru"

    private val filler = setOf(
        "tolong", "coba", "bisa", "mohon", "please", "hey", "halo", "hai", "hi",
        "buatkan", "buat", "bikin", "kerjakan", "kerja", "make", "create", "build",
        "aku", "saya", "i", "me", "kamu", "you", "dong", "ya", "yang", "untuk",
    )

    /**
     * A short, human title from the first prompt: first clause, filler words
     * dropped, capped so the drawer row stays one line. Works for Indonesian and
     * English without a tokenizer.
     */
    fun fromPrompt(prompt: String): String {
        val cleaned = prompt
            .replace(Regex("```[\\s\\S]*?```"), " ")   // code blocks carry no title
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()

        val firstClause = cleaned
            .split(Regex("(?<=[.!?])\\s+|\\s*[—–]\\s*|,\\s*"))
            .firstOrNull { it.isNotBlank() }
            ?: cleaned

        val words = firstClause.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            // Keep proper nouns and code identifiers (upper-case or ./: inside).
            .filter { w ->
                w.matches(Regex(".*[A-Z].*")) || w.contains('/') || w.contains('.') ||
                    w.lowercase().trim(',', ':', ';', '"', '\'') !in filler
            }

        val title = (if (words.isEmpty()) firstClause else words.joinToString(" "))
            .trim()
            .take(60)
            .trimEnd(' ', ',', ':', ';', '-')

        return title.ifBlank { NEW }
    }
}
