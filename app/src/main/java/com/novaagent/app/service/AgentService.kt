package com.novaagent.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.novaagent.app.MainActivity
import com.novaagent.app.R
import com.novaagent.app.agent.AgentLoop
import com.novaagent.app.bridge.TermuxBridge
import com.novaagent.app.agent.ToolRound
import com.novaagent.app.data.AgentState
import com.novaagent.app.data.ChatDb
import com.novaagent.app.data.Limits
import com.novaagent.app.data.MemoryFiles
import com.novaagent.app.data.Message
import com.novaagent.app.data.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the agent loop so a turn survives the Activity being backgrounded or
 * destroyed. The UI writes the user message to Room, then asks this service to
 * run the turn; it reads Room again when `turnDone` changes.
 *
 * No wall-clock watchdog: a long build must be allowed to finish. The brake is
 * the per-turn tool-round cap; when it trips the notification grows "Lanjut" and
 * "Prompt baru" actions instead of silently killing the turn.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val manager by lazy { getSystemService(NotificationManager::class.java) }

    /** Rounds executed in the running turn, rendered live in the notification. */
    @Volatile private var roundCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val sessionId = intent?.getLongExtra(EXTRA_SESSION, 0L) ?: 0L

        when (action) {
            ACTION_CONTINUE -> if (sessionId != 0L) {
                // "lanjut": same thread, fresh round budget.
                startTurn(sessionId, continueThread = true)
                return START_STICKY
            }
            ACTION_NEW_PROMPT -> {
                // Handled by the UI through AgentState; nothing to run here.
                AgentState.requestPrompt.value += 1
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RUN -> if (sessionId != 0L) startTurn(sessionId, continueThread = true)
        }

        if (action != ACTION_RUN && action != ACTION_CONTINUE) {
            // Bare start (system restart): stay alive without claiming a turn.
            startForegroundCompat()
            return START_STICKY
        }
        startForegroundCompat()
        return START_STICKY
    }

    private fun startTurn(sessionId: Long, continueThread: Boolean) {
        if (job?.isActive == true) {
            AgentState.error.value = "Masih ada satu task berjalan. Tunggu sampai selesai."
            return
        }
        startForegroundCompat()
        AgentState.running.value = true
        roundCount = 0

        job = scope.launch {
            AgentState.busy.value = true
            AgentState.error.value = null
            AgentState.interrupt.value = false
            val dao = ChatDb.of(this@AgentService).chat()
            try {
                val session = dao.session(sessionId)
                val title = dao.contents(sessionId).firstOrNull()?.take(60) ?: "sesi-$sessionId"
                val defaultDir = Workspace.defaultDir(this@AgentService)

                // One setting only: the default root. The agent gives each session its
                // own folder under it, created lazily the first time a tool writes.
                val workdir = Workspace.resolve(this@AgentService, sessionId).ifBlank { defaultDir }
                val ensureSessionDir: () -> String = {
                    val existing = Workspace.sessionDir(this@AgentService, sessionId)
                    if (existing.isNotBlank()) {
                        existing
                    } else if (defaultDir.isBlank()) {
                        "" // no default dir configured: stay in Termux HOME
                    } else {
                        val path = Workspace.dirFor(this@AgentService, sessionId, title)
                        val r = TermuxBridge.run(
                            this@AgentService,
                            "mkdir -p '$path' && cd '$path' && pwd",
                            TIMEOUT_DIR_MS,
                        )
                        if (r.exitCode == 0 && r.error == null) {
                            Workspace.setSessionDir(this@AgentService, sessionId, path)
                            AgentState.status.value = "workdir: $path"
                            AgentState.sessionDir.value = path
                            AgentState.workdirVersion.value += 1
                            path
                        } else {
                            "" // mkdir failed: fall through to the default dir, never abort the turn
                        }
                    }
                }

                val history = buildHistory(dao, sessionId, continueThread)
                val prompt = MemoryFiles.systemPrompt(this@AgentService, workdir)
                val maxRounds = Limits.maxRounds(this@AgentService)

                val result = AgentLoop.run(
                    context = this@AgentService,
                    history = history,
                    system = prompt.text,
                    workdir = workdir,
                    maxRounds = maxRounds,
                    ensureSessionDir = ensureSessionDir,
                    onTool = { round ->
                        roundCount += 1
                        AgentState.status.value = AgentLoop.toolMarker(round.name, round.detail)
                        notifyProgress(AgentState.status.value)
                    },
                )

                dao.insertMessage(
                    Message(
                        sessionId = sessionId,
                        role = "assistant",
                        content = result.text,
                        createdAt = System.currentTimeMillis(),
                        rounds = encodeRounds(result.rounds),
                    )
                )
                dao.touchSession(sessionId, title.take(40), System.currentTimeMillis())

                // Memory grows with use, not with every turn: only a limit trip is
                // worth persisting without the model asking (it records where it got stuck).
                if (result.hitLimit) {
                    MemoryFiles.append(
                        this@AgentService,
                        Workspace.resolve(this@AgentService, sessionId),
                        "PROJECT",
                        "Turn ${sessionId} mencapai batas $maxRounds putaran tool " +
                            "($roundCount tool). Terakhir: ${AgentState.status.value}",
                    )
                    AgentState.limitHit.value = sessionId
                    notifyLimit(sessionId, maxRounds)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: an IllegalArgumentException from a header
                // field or an OutOfMemory from a huge tool result used to escape here
                // and take the process down mid-turn. A failed turn must never close
                // the app.
                Log.e(TAG, "turn failed", e)
                AgentState.error.value = e.message ?: e.javaClass.simpleName
                runCatching {
                    dao.insertMessage(
                        Message(
                            sessionId = sessionId,
                            role = "assistant",
                            content = "Error: " + (e.message ?: e.javaClass.simpleName),
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
            } finally {
                AgentState.busy.value = false
                AgentState.status.value = ""
                notifyProgress("")
                AgentState.turnDone.value += 1
            }
        }
    }

    /**
     * Rebuilds provider history from Room. On a "lanjut" the previous turn hit the
     * round cap, so its stored assistant text is a limit notice — replay it as a
     * user nudge ("lanjut") instead of pretending the model said it.
     */
    private suspend fun buildHistory(
        dao: com.novaagent.app.data.ChatDao,
        sessionId: Long,
        continueThread: Boolean,
    ): MutableList<JSONObject> {
        val rows = dao.messages(sessionId)
        val history = mutableListOf<JSONObject>()
        rows.forEach { row ->
            when (row.role) {
                "user" -> history.add(AgentLoop.userMessage(row.content))
                "assistant" -> {
                    if (row.content.isNotBlank()) {
                        val isLimitNotice = row.content.startsWith("Batas ")
                        if (isLimitNotice) {
                            if (continueThread) history.add(AgentLoop.userMessage("lanjut"))
                        } else {
                            history.add(AgentLoop.assistantMessage(row.content))
                        }
                    }
                }
            }
        }
        if (continueThread && history.lastOrNull()?.optString("role") != "user") {
            history.add(AgentLoop.userMessage("lanjut"))
        }
        return history
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        AgentState.running.value = false
        AgentState.busy.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyProgress(statusLine: String) {
        manager.notify(NOTIFICATION_ID, buildNotification(statusLine))
    }

    /** Turn limit reached: the notification offers continue vs new prompt. */
    private fun notifyLimit(sessionId: Long, maxRounds: Int) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(getString(R.string.notif_limit_title))
            .setContentText(getString(R.string.notif_limit_text, maxRounds))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.notif_limit_text, maxRounds))
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.notif_limit_continue), actionIntent(ACTION_CONTINUE, sessionId))
            .addAction(0, getString(R.string.notif_limit_new), actionIntent(ACTION_NEW_PROMPT, sessionId))
            .setContentIntent(actionIntent(ACTION_NEW_PROMPT, sessionId))
            .build()
        manager.notify(LIMIT_NOTIFICATION_ID, n)
    }

    private fun actionIntent(action: String, sessionId: Long): PendingIntent {
        val intent = Intent(this, AgentService::class.java)
            .setAction(action)
            .putExtra(EXTRA_SESSION, sessionId)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun encodeRounds(rounds: List<ToolRound>): String {
        if (rounds.isEmpty()) return ""
        val arr = JSONArray()
        rounds.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("name", r.name)
                    put("detail", r.detail)
                    put("thought", r.thought)
                    put("result", r.result)
                }
            )
        }
        return arr.toString()
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_agent),
                    // DEFAULT, not LOW: the limit notification has actionable buttons.
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }

    private fun buildNotification(statusLine: String = AgentState.status.value): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            statusLine.isNotBlank() -> statusLine
            AgentState.busy.value -> getString(R.string.notif_working)
            else -> getString(R.string.notif_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "novaagent_agent"
        private const val NOTIFICATION_ID = 1
        private const val LIMIT_NOTIFICATION_ID = 2
        private const val ACTION_RUN = "com.novaagent.app.RUN_TURN"
        private const val ACTION_CONTINUE = "com.novaagent.app.CONTINUE_TURN"
        private const val ACTION_NEW_PROMPT = "com.novaagent.app.NEW_PROMPT"
        private const val EXTRA_SESSION = "sessionId"
        private const val TIMEOUT_DIR_MS = 20_000

        fun startTurn(context: Context, sessionId: Long) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_SESSION, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun continueTurn(context: Context, sessionId: Long) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_CONTINUE)
                .putExtra(EXTRA_SESSION, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
