package com.novaagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novaagent.app.ui.ChatScreen
import com.novaagent.app.ui.ChatViewModel
import com.novaagent.app.ui.PermissionScreen
import com.novaagent.app.ui.SettingsScreen
import com.novaagent.app.ui.theme.NovaAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovaAgentTheme { Root() }
        }
    }
}

private enum class Screen { Permissions, Chat, Settings }

@Composable
private fun Root() {
    val vm: ChatViewModel = viewModel()
    val prefs = LocalContext.current.getSharedPreferences("novaagent", 0)
    var screen by remember {
        mutableStateOf(if (prefs.getBoolean("onboarded", false)) Screen.Chat else Screen.Permissions)
    }

    BackHandler(enabled = screen == Screen.Settings) {
        vm.reloadLanguage()
        screen = Screen.Chat
    }

    when (screen) {
        Screen.Permissions -> PermissionScreen(onDone = {
            prefs.edit().putBoolean("onboarded", true).apply()
            screen = Screen.Chat
        })
        Screen.Chat -> ChatScreen(vm = vm, onOpenSettings = { screen = Screen.Settings })
        Screen.Settings -> SettingsScreen(onBack = {
            vm.reloadLanguage()
            screen = Screen.Chat
        })
    }
}
