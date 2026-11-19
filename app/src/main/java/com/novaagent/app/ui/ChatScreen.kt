package com.novaagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.novaagent.app.data.UiMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Sesi",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                TextButton(
                    onClick = { vm.newSession(); scope.launch { drawer.close() } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text("+ Sesi baru") }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(sessions, key = { it.id }) { s ->
                        TextButton(
                            onClick = { vm.openSession(s.id); scope.launch { drawer.close() } },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        ) { Text(s.title, maxLines = 1) }
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("NovaAgent", maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Text("☰", style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Text("⚙", style = MaterialTheme.typography.titleMedium)
                        }
                    },
                )
            },
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(inner).imePadding()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { Bubble(it) }
                }

                error?.let { msg ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = vm::clearError) { Text("Tutup") }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Tanya atau ketik /help") },
                        maxLines = 6,
                    )
                    Box(Modifier.width(8.dp))
                    TextButton(
                        enabled = !busy && draft.isNotBlank(),
                        onClick = { vm.send(draft); draft = "" },
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                        else Text("Kirim")
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: UiMessage) {
    val isUser = m.role == "user"
    val isTool = m.role == "tool"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = if (isTool) 300.dp else 320.dp)
                .background(
                    when {
                        isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        isTool -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                    RoundedCornerShape(if (isTool) 8.dp else 14.dp),
                )
                .padding(horizontal = 12.dp, vertical = if (isTool) 6.dp else 12.dp),
        ) {
            if (isTool) {
                Text(
                    m.text,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    Text(
                        m.text,
                        style = if (m.text.contains("```")) {
                            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    )
                }
            }
        }
    }
}
