package com.novaagent.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedTextField
import androidx.core.content.ContextCompat
import com.novaagent.app.bridge.TermuxBridge
import com.novaagent.app.data.AgentState
import com.novaagent.app.data.AppFiles
import com.novaagent.app.data.Limits
import com.novaagent.app.data.MemoryFiles
import com.novaagent.app.data.ProviderConfig
import com.novaagent.app.data.Settings
import com.novaagent.app.data.Skills
import com.novaagent.app.data.Workspace
import com.novaagent.app.service.AgentService
import com.novaagent.app.ui.theme.Spacing
import com.novaagent.app.ui.theme.monoStyle

/**
 * Settings. Deliberately short: the only workdir setting is the default root —
 * per-session folders are the agent's job, so they are not configurable here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved by Settings.config.collectAsState()
    val running by AgentState.running.collectAsState()

    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var defaultDir by remember { mutableStateOf(Workspace.defaultDir(context)) }
    var capMb by remember { mutableStateOf(Limits.memoryCapMb(context)) }
    var rounds by remember { mutableStateOf(Limits.maxRounds(context)) }
    var autoWrite by remember { mutableStateOf(Limits.isAutoWrite(context)) }
    var skillEpoch by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }

    val termuxStatus = TermuxBridge.status(context)
    val workdir = Workspace.defaultDir(context)
    val skills = remember(skillEpoch, workdir) { Skills.all(context, workdir) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Kembali") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Section("Provider")
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            BodyText("Endpoint: " + ProviderConfig(baseUrl, apiKey, model).chatEndpoint)
            TextButton(onClick = {
                Settings.update(ProviderConfig(baseUrl.trim(), apiKey.trim(), model.trim()))
                onBack()
            }) { Text("Simpan") }

            Divider()

            Section("Workdir")
            BodyText(
                "Satu setting saja: folder induk. Agent membuat folder per sesi di dalamnya " +
                    "saat pertama kali menulis file. Terlihat juga di header chat.",
            )
            Field(
                value = defaultDir,
                onValueChange = { defaultDir = it },
                placeholder = AppFiles.media(context).absolutePath,
            )
            Row {
                TextButton(onClick = {
                    Workspace.setDefaultDir(context, defaultDir)
                    AgentState.workdirVersion.value += 1
                    note = "Default workdir disimpan."
                }) { Text("Simpan dir") }
                TextButton(onClick = {
                    defaultDir = AppFiles.media(context).absolutePath
                    Workspace.setDefaultDir(context, defaultDir)
                    AgentState.workdirVersion.value += 1
                    note = "Kembali ke folder bawaan app."
                }) { Text("Pakai bawaan") }
            }

            Divider()

            Divider()

            Section("Skill")
            BodyText(
                "${skills.size} skill terpasang. System prompt hanya memuat nama + deskripsi; " +
                    "agent memanggil use_skill untuk membaca isinya.",
            )
            if (skills.isEmpty()) {
                BodyText("Belum ada. Taruh folder skill (berisi SKILL.md) di:\n" +
                    java.io.File(context.filesDir, "skills").absolutePath)
            } else {
                skills.forEach { s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                s.description.take(100),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            note = Skills.remove(context, Workspace.defaultDir(context), s.name)
                            skillEpoch++
                        }) { Text("Hapus") }
                    }
                }
            }

            Divider()

            Section("Batas agent")
            BodyText("$rounds putaran tool per turn. Saat tercapai: Lanjut atau prompt baru. " +
                "Tidak ada watchdog waktu — perintah panjang dibiarkan selesai.")
            Slider(
                value = rounds.toFloat(),
                onValueChange = { rounds = ((it / 5).toInt() * 5).coerceIn(5, 60) },
                onValueChangeFinished = { Limits.setMaxRounds(context, rounds) },
                valueRange = 5f..60f,
                steps = 10,
            )
            BodyText(
                "Memori per command: " + (if (capMb == 0) "tanpa batas" else "$capMb MB (ulimit -v)"),
            )
            Slider(
                value = capMb.toFloat(),
                onValueChange = { capMb = (it / 64).toInt() * 64 },
                onValueChangeFinished = { Limits.setMemoryCapMb(context, capMb) },
                valueRange = 0f..2048f,
            )

            Divider()

            Section("Memory")
            BodyText(
                MemoryFiles.status(context, Workspace.resolve(context, 0L)),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tulis memory otomatis", style = MaterialTheme.typography.bodyMedium)
                    BodyText(
                        if (autoWrite) "Agent menulis SOUL.md / USER.md / MEMORY.md sendiri."
                        else "Agent mengusulkan, kamu konfirmasi di chat.",
                    )
                }
                Switch(
                    checked = autoWrite,
                    onCheckedChange = {
                        autoWrite = it
                        Limits.setMemoryWritePolicy(
                            context,
                            if (it) Limits.POLICY_AUTO else Limits.POLICY_CONFIRM,
                        )
                    },
                )
            }

            Divider()

            Section("Shell")
            BodyText(termuxStatus)
            BodyText(
                "Shell bawaan ikut di dalam app — tidak perlu Termux atau Termux:API. " +
                    "File kerja ada di Android/media/" + context.packageName + ".",
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Agent service", style = MaterialTheme.typography.bodyMedium)
                    BodyText(if (running) "Berjalan" else "Berhenti")
                }
                Switch(
                    checked = running,
                    onCheckedChange = { on ->
                        val intent = Intent(context, AgentService::class.java)
                        if (on) ContextCompat.startForegroundService(context, intent)
                        else context.stopService(intent)
                    },
                )
            }

            if (note.isNotBlank()) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .padding(Spacing.md),
                )
            }

            Divider()

            BodyText(
                "API key belum disimpan permanen — masih di memori proses. " +
                    "EncryptedSharedPreferences masuk sebelum rilis.",
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = Spacing.sm))

@Composable
private fun Field(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = monoStyle(13).copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, style = monoStyle(13), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            inner()
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
            .padding(Spacing.md),
    )
}
