package com.novaagent.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.novaagent.app.data.AppFiles
import com.novaagent.app.ui.theme.Spacing
import com.novaagent.app.ui.theme.monoStyle

/**
 * First-launch permission offer. Only requests what the app will actually use —
 * there is no broad-storage ask because the app owns its media dir, and no Termux
 * permission because the shell is bundled.
 */
@Composable
fun PermissionScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(hasNotifications(context)) }
    val asks = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifications = granted }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Izin aplikasi") }) },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                "NovaAgent hanya minta yang dipakai. Bisa diubah kapan saja di Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PermissionCard(
                icon = Icons.Default.Notifications,
                title = "Notifikasi",
                granted = notifications,
                detail = "Menampilkan status agent saat berjalan di latar belakang, " +
                    "termasuk tombol Lanjut saat batas putaran tercapai.",
                onRequest = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        asks.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onDone()
                    }
                },
            )

            PermissionCard(
                icon = Icons.Default.Storage,
                title = "Folder file",
                granted = true,
                detail = "File agent tersimpan di " + AppFiles.media(context).absolutePath +
                    " — terbuka di file manager tanpa izin tambahan.",
                onRequest = {},
            )

            HorizontalDivider()
            Text(
                "Baterai: sebagian HP menghentikan agent di latar belakang. Buka " +
                    "pengaturan baterai kalau agent berhenti sendiri.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) { Text("Buka pengaturan baterai") }

            Spacer(Modifier.size(Spacing.sm))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Mulai") }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    granted: Boolean,
    detail: String,
    onRequest: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(Spacing.sm))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (granted) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Diizinkan",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (!granted) {
                TextButton(onClick = onRequest) { Text("Izinkan") }
            }
        }
    }
}

private fun hasNotifications(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
