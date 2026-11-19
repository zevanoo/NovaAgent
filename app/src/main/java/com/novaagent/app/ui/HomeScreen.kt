package com.novaagent.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.novaagent.app.data.AgentState
import com.novaagent.app.service.AgentService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val running by AgentState.running.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("NovaAgent") }) }) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(if (running) "Agent service: running" else "Agent service: stopped")
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val intent = Intent(context, AgentService::class.java)
                    if (running) context.stopService(intent)
                    else ContextCompat.startForegroundService(context, intent)
                }
            ) {
                Text(if (running) "Stop" else "Start")
            }
        }
    }
}
