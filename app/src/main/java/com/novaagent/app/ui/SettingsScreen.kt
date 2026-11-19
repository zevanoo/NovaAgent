package com.novaagent.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.novaagent.app.bridge.TermuxBridge
import com.novaagent.app.data.AgentState
import com.novaagent.app.data.Limits
import com.novaagent.app.data.MemoryFiles
import com.novaagent.app.data.ProviderConfig
import com.novaagent.app.data.Settings
import com.novaagent.app.data.Workspace
import com.novaagent.app.service.AgentService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val saved by Settings.config.collectAsState()
    val running by AgentState.running.collectAsState()
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var workdir by remember { mutableStateOf(Workspace.get(context)) }
    var capMb by remember { mutableStateOf(Limits.memoryCapMb(context)) }
    // Computed on every recomposition so returning from the system permission dialog refreshes it.
    val termuxStatus = TermuxBridge.status(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Kembali") } },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text("Provider", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "Endpoint: " + ProviderConfig(baseUrl, apiKey, model).chatEndpoint,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextButton(onClick = {
                    Settings.update(ProviderConfig(baseUrl.trim(), apiKey.trim(), model.trim()))
                    onBack()
                }) { Text("Simpan") }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text("Bridge Termux", style = MaterialTheme.typography.titleMedium)
            Text(termuxStatus, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = workdir,
                onValueChange = { workdir = it },
                label = { Text("Working dir (kosong = HOME Termux)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row {
                TextButton(onClick = { Workspace.set(context, workdir) }) { Text("Simpan dir") }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    TextButton(onClick = {
                        if (!Environment.isExternalStorageManager()) {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:" + context.packageName))
                            )
                        }
                    }) { Text("Izin storage") }
                }
            }
            Text(
                "Satu kali di Termux: set `allow-external-apps=true` di " +
                    "~/.termux/termux.properties, lalu restart Termux.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text("Batas memori per command", style = MaterialTheme.typography.titleMedium)
            Text(
                if (capMb == 0) "Tanpa batas — satu perintah bisa menghabiskan RAM dan memicu force-close."
                else "$capMb MB (ulimit -v)",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = capMb.toFloat(),
                onValueChange = { capMb = (it / 64).toInt() * 64 },
                onValueChangeFinished = { Limits.setMemoryCapMb(context, capMb) },
                valueRange = 0f..2048f,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Agent service", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (running) "Berjalan" else "Berhenti",
                        style = MaterialTheme.typography.bodySmall,
                    )
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

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text("Memory", style = MaterialTheme.typography.titleMedium)
            Text(
                MemoryFiles.status(context),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text(
                "API key belum disimpan permanen — masih di memori proses. " +
                    "EncryptedSharedPreferences masuk sebelum rilis.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
