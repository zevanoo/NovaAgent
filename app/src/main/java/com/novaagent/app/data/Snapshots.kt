package com.novaagent.app.data

import android.content.Context
import com.novaagent.app.bridge.TermuxBridge
import org.json.JSONObject

/**
 * Revert support.
 *
 * Every turn that may write files takes a git snapshot of the workdir first, so
 * a revert can put the files back exactly as they were. Git is used instead of a
 * hand-rolled copy/mtime ledger because Termux already ships it, it handles
 * renames and deletions, and "revert the working tree to tree X" is one command.
 *
 * A snapshot is a commit hash stored per message row. Reverting a user turn means
 * `git checkout <hash> -- .` plus cleaning untracked files the turn created, then
 * dropping the messages. When the workdir is not a git repo we initialise one, so
 * the very first turn is already revertible.
 */
object Snapshots {

    /** true when the workdir has a usable repo; created on demand. */
    fun ensure(context: Context, workdir: String): Boolean {
        if (workdir.isBlank()) return false
        val r = TermuxBridge.run(
            context,
            "cd ${q(workdir)} || exit 3; " +
                "if [ ! -d .git ]; then git init -q 2>/dev/null || exit 4; fi; " +
                "git rev-parse --git-dir >/dev/null 2>&1 || exit 5; echo ok",
            timeoutMs = 20_000,
        )
        return r.exitCode == 0 && r.output.contains("ok")
    }

    /**
     * Commits whatever is in the workdir and returns the commit hash, or null when
     * snapshots are impossible (no git, no workdir).
     */
    fun capture(context: Context, workdir: String, label: String): String? {
        if (!ensure(context, workdir)) return null
        val msg = label.replace('\'', ' ').take(80)
        // `add -A` then commit; an empty tree (nothing changed) still needs a
        // stable hash, so `git stash create` is not used - --allow-empty is simpler.
        val cmd = "cd ${q(workdir)} && " +
            "git add -A 2>/dev/null; " +
            "git -c user.email=nova@local -c user.name=novaagent commit -q --allow-empty -m ${q(msg)} 2>/dev/null; " +
            "git rev-parse HEAD"
        val r = TermuxBridge.run(context, cmd, timeoutMs = 60_000)
        val hash = r.output.lineSequence().lastOrNull { it.trim().length >= 7 }?.trim()
        return hash?.takeIf { it.matches(Regex("[0-9a-f]{7,40}")) }
    }

    /** Restores the workdir to [hash] and removes files created since then. */
    fun restore(context: Context, workdir: String, hash: String): String {
        if (workdir.isBlank()) return "Error: workdir kosong."
        if (!hash.matches(Regex("[0-9a-f]{7,40}"))) return "Error: snapshot tidak valid."
        val cmd = "cd ${q(workdir)} || exit 3; " +
            "git checkout -q ${q(hash)} -- . 2>&1 && " +
            "git clean -fdq 2>&1; " +
            "echo REVERTED"
        val r = TermuxBridge.run(context, cmd, timeoutMs = 120_000)
        return if (r.output.contains("REVERTED")) "OK: file dikembalikan." else "Error: ${r.output}"
    }

    fun describe(context: Context, workdir: String): JSONObject = JSONObject().apply {
        put("workdir", workdir)
        put("git", ensure(context, workdir))
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
