package com.novaagent.app.data

import android.content.Context

/**
 * Two languages, no framework.
 *
 * Android resources would mean a dozen `getString` calls threaded through
 * composables; the UI only needs a handful of labels, so a map plus one current
 * value covers it. Switching takes effect immediately because every screen
 * observes [lang].
 *
 * The system prompt language follows this too, so an English UI gets an English
 * agent instead of a mixed one.
 */
object I18n {

    const val ID = "id"
    const val EN = "en"

    private const val KEY = "lang"

    private val strings = mapOf(
        ID to mapOf(
            "sessions" to "Sesi",
            "new_session" to "Sesi baru",
            "settings" to "Setelan",
            "back" to "Kembali",
            "stop" to "Stop",
            "interrupt" to "Hentikan",
            "retry" to "Ulangi",
            "revert" to "Kembalikan",
            "delete" to "Hapus",
            "cancel" to "Batal",
            "save" to "Simpan",
            "revert_title" to "Kembalikan prompt ini?",
            "revert_body" to "Semua perubahan file dari prompt ini dan balasannya akan diurungkan, lalu pesannya dihapus.",
            "revert_files" to "File di workdir ikut dikembalikan.",
            "delete_title" to "Hapus pesan?",
            "delete_session" to "Hapus sesi",
            "workdir" to "Workdir",
            "this_session" to "Sesi ini",
            "default_workdir" to "Default workdir",
            "composer_hint" to "Tanya atau ketik /help",
            "send" to "Kirim",
            "thinking" to "Berjalan...",
            "limit_banner" to "Batas putaran tool tercapai.",
            "continue" to "Lanjut",
            "new_prompt" to "Prompt baru",
            "close" to "Tutup",
            "save_memory" to "Simpan",
            "reject_memory" to "Tolak",
            "empty_title" to "NovaAgent",
            "empty_body" to "Agent di Android lewat Termux. Ketik /help untuk daftar command.",
            "permissions_title" to "Izin aplikasi",
            "permissions_intro" to "NovaAgent hanya minta yang dipakai. Bisa diubah kapan saja di Setelan.",
            "notifications" to "Notifikasi",
            "notifications_detail" to "Status agent saat berjalan di latar belakang, termasuk tombol Lanjut saat batas putaran tercapai.",
            "grant" to "Izinkan",
            "granted" to "Diizinkan",
            "folder" to "Folder kerja",
            "folder_detail" to "File agent tersimpan di workdir Termux. Terbuka di file manager mana pun.",
            "battery_note" to "Sebagian HP menghentikan agent di latar belakang. Buka pengaturan baterai kalau agent berhenti sendiri.",
            "battery_button" to "Buka pengaturan baterai",
            "start" to "Mulai",
            "language" to "Bahasa",
            "title" to "NovaAgent",
        ),
        EN to mapOf(
            "sessions" to "Sessions",
            "new_session" to "New session",
            "settings" to "Settings",
            "back" to "Back",
            "stop" to "Stop",
            "interrupt" to "Interrupt",
            "retry" to "Retry",
            "revert" to "Revert",
            "delete" to "Delete",
            "cancel" to "Cancel",
            "save" to "Save",
            "revert_title" to "Revert this prompt?",
            "revert_body" to "Every file change from this prompt and its reply will be undone, then both messages are removed.",
            "revert_files" to "Files in the workdir are restored too.",
            "delete_title" to "Delete message?",
            "delete_session" to "Delete session",
            "workdir" to "Workdir",
            "this_session" to "This session",
            "default_workdir" to "Default workdir",
            "composer_hint" to "Ask anything or type /help",
            "send" to "Send",
            "thinking" to "Working...",
            "limit_banner" to "Tool round limit reached.",
            "continue" to "Continue",
            "new_prompt" to "New prompt",
            "close" to "Close",
            "save_memory" to "Save",
            "reject_memory" to "Discard",
            "empty_title" to "NovaAgent",
            "empty_body" to "An agent on Android through Termux. Type /help for commands.",
            "permissions_title" to "App permissions",
            "permissions_intro" to "NovaAgent only asks for what it uses. Change this any time in Settings.",
            "notifications" to "Notifications",
            "notifications_detail" to "Agent status while running in the background, including Continue when the round limit is hit.",
            "grant" to "Allow",
            "granted" to "Allowed",
            "folder" to "Working folder",
            "folder_detail" to "Agent files live in the Termux workdir, readable from any file manager.",
            "battery_note" to "Some phones stop background agents. Open battery settings if the agent stops on its own.",
            "battery_button" to "Open battery settings",
            "start" to "Start",
            "language" to "Language",
            "title" to "NovaAgent",
        ),
    )

    /** Random welcome lines shown on the draft screen, so the app never opens blank. */
    private val welcome = mapOf(
        ID to listOf(
            "Mau bikin apa hari ini?",
            "Sebutkan satu tugas, agent yang urus sisanya.",
            "Folder kerja sudah siap. Ketik prompt pertama.",
            "Bisa baca, tulis, dan jalankan perintah lewat Termux.",
            "Mulai dari yang kecil — satu file, satu perintah.",
            "Ketik /help untuk lihat semua command.",
            "Agent ingat konteks antar sesi dalam satu project.",
        ),
        EN to listOf(
            "What are we building today?",
            "Describe one task, the agent handles the rest.",
            "The workdir is ready. Type your first prompt.",
            "It can read, write and run commands through Termux.",
            "Start small — one file, one command.",
            "Type /help to see every command.",
            "The agent keeps project context across sessions.",
        ),
    )

    fun current(context: Context): String =
        prefs(context).getString(KEY, null) ?: defaultForLocale()

    fun set(context: Context, lang: String) {
        prefs(context).putString(KEY, if (lang == EN) EN else ID).apply()
    }

    fun isEnglish(context: Context): Boolean = current(context) == EN

    /** Looks a key up in the current language, falling back to Indonesian. */
    fun t(context: Context, key: String): String =
        strings[current(context)]?.get(key) ?: strings[ID]?.get(key) ?: key

    fun welcome(context: Context): String {
        val list = welcome[current(context)] ?: welcome[ID].orEmpty()
        return list.random()
    }

    /** Instruction appended to the system prompt so the agent replies in the UI language. */
    fun promptRule(context: Context): String =
        if (isEnglish(context)) "Reply in English. Keep tool output verbatim."
        else "Balas dalam bahasa Indonesia. Output tool biarkan apa adanya."

    fun languages(): List<Pair<String, String>> =
        listOf(ID to "Bahasa Indonesia", EN to "English")

    private fun defaultForLocale(): String {
        val tag = java.util.Locale.getDefault().language
        return if (tag.equals("in", true) || tag.equals("id", true)) ID else EN
    }

    private fun prefs(context: Context) = context.getSharedPreferences("novaagent", 0)
}
