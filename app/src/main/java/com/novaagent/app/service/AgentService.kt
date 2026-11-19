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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.novaagent.app.MainActivity
import com.novaagent.app.R
import com.novaagent.app.agent.AgentLoop
import com.novaagent.app.data.AgentState
import com.novaagent.app.data.ChatDb
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the agent loop so a turn survives the Activity being backgrounded or
 * destroyed. The UI writes the user message to Room, then asks this service to
 * run the turn; it reads Room again when `turnDone` changes.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val manager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        AgentState.running.value = true

        if (intent?.action == ACTION_RUN) {
            val sessionId = intent.getLongExtra(EXTRA_SESSION, 0L)
            if (sessionId != 0L) startTurn(sessionId)
        }
        return START_STICKY
    }

    private fun startTurn(sessionId: Long) {
        if (job?.isActive == true) {
            AgentState.error.value = "Masih ada satu task berjalan. Tunggu sampai selesai."
            return
        }
        job = scope.launch {
            AgentState.busy.value = true
            AgentState.error.value = null
            val dao = ChatDb.of(this@AgentService).chat()
            try {
                val history = dao.messages(sessionId).mapNotNull { row ->
                    when (row.role) {
                        "user" -> AgentLoop.userMessage(row.content)
                        "assistant" -> row.content
                            .takeIf { it.isNotBlank() }
                            ?.let { AgentLoop.assistantMessage(it) }
                        else -> null
                    }
                }.toMutableList()

                val system = MemoryFiles.read(this@AgentService, MemoryFiles.SOUL)
                    .orEmpty().ifBlank { DEFAULT_SYSTEM }

                // Wall-clock watchdog. Each command has its own timeout; this one
                // bounds a provider that keeps emitting tool calls forever.
                val reply = withTimeoutOrNull(TURN_TIMEOUT_MS) {
                    AgentLoop.run(
                        context = this@AgentService,
                        history = history,
                        system = system,
                        workdir = Workspace.get(this@AgentService),
                        onTool = { name, detail ->
                            val line = AgentLoop.toolMarker(name, detail)
                            AgentState.status.value = line
                            notifyProgress(line)
                        },
                    )
                }

                if (reply == null) {
                    AgentState.error.value = "Timeout ${TURN_TIMEOUT_MS / 60_000} menit — task dihentikan."
                } else {
                    dao.insertMessage(
                        Message(
                            sessionId = sessionId,
                            role = "assistant",
                            content = reply,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                    val title = dao.contents(sessionId).firstOrNull()?.take(40) ?: "Sesi baru"
                    dao.touchSession(sessionId, title, System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentState.error.value = e.message ?: e.javaClass.simpleName
            } finally {
                AgentState.busy.value = false
                AgentState.status.value = ""
                notifyProgress("")
                AgentState.turnDone.value += 1
            }
        }
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

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_agent),
                    NotificationManager.IMPORTANCE_LOW,
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
        private const val CHANNEL_ID = "novaagent_agent"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_RUN = "com.novaagent.app.RUN_TURN"
        private const val EXTRA_SESSION = "sessionId"
        private const val TURN_TIMEOUT_MS = 10 * 60 * 1000L

        private const val DEFAULT_SYSTEM =
            "Kamu adalah NovaAgent, coding agent di Android. Jawab ringkas, tunjukkan kode konkret."

        fun startTurn(context: Context, sessionId: Long) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_SESSION, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
