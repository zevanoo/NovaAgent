package com.novaagent.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novaagent.app.ui.ChatScreen
import com.novaagent.app.ui.ChatViewModel
import com.novaagent.app.ui.SettingsScreen
import com.novaagent.app.ui.theme.NovaAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovaAgentTheme {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val ask = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { }
                    LaunchedEffect(Unit) { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
                Root()
            }
        }
    }
}

@Composable
private fun Root() {
    val vm: ChatViewModel = viewModel()
    var settings by remember { mutableStateOf(false) }

    BackHandler(enabled = settings) { settings = false }

    if (settings) SettingsScreen(onBack = { settings = false })
    else ChatScreen(vm = vm, onOpenSettings = { settings = true })
}
