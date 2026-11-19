package com.novaagent.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novaagent.app.data.AgentState
import com.novaagent.app.data.UiMessage
import com.novaagent.app.data.UiRound
import com.novaagent.app.ui.theme.Spacing
import com.novaagent.app.ui.theme.monoStyle
import kotlinx.coroutines.launch

/**
 * Claude-app-shaped chat: a quiet top bar that always shows where commands run,
 * one scrolling transcript, and a composer pinned above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()
    val limitHit by vm.limitHit.collectAsState()
    val pendingVersion by vm.pendingMemory.collectAsState()
    val workdirVersion by AgentState.workdirVersion.collectAsState()
    val sessionDir by AgentState.sessionDir.collectAsState()
    val pendingMemory = remember(pendingVersion) { vm.pendingWrites() }
    val workdir = remember(workdirVersion, sessionDir, vm.sessionId) { vm.workdirLabel() }

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var showWorkdir by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
            ) {
                Text(
                    "Sesi",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(Spacing.lg),
                )
                TextButton(
                    onClick = { vm.newSession(); scope.launch { drawer.close() } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
                ) {
                    Text("+ Sesi baru", color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(sessions, key = { it.id }) { s ->
                        val selected = s.id == vm.sessionId
                        TextButton(
                            onClick = { vm.openSession(s.id); scope.launch { drawer.close() } },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
                        ) {
                            Text(
                                s.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    title = {
                        Column(Modifier.clickable { showSessions = true }) {
                            Text("NovaAgent", style = MaterialTheme.typography.titleMedium)
                            // Working dir is visible without opening Settings.
                            Text(
                                "@ $workdir",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 1.dp)
                                    .clickable { showWorkdir = true },
                            )
                        }
                    },
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
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    if (messages.isEmpty()) item { EmptyState() }
                    items(messages, key = { it.id }) { m ->
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Bubble(m)
                            if (m.rounds.isNotEmpty()) ToolRows(m.rounds)
                        }
                    }
                }

                error?.let { msg ->
                    Banner(
                        text = msg,
                        tone = MaterialTheme.colorScheme.error,
                        actionLabel = "Tutup",
                        onAction = vm::clearError,
                    )
                }

                pendingMemory.forEach { item ->
                    Banner(
                        text = "Simpan ke ${item.target}: ${item.note.take(70)}",
                        tone = MaterialTheme.colorScheme.primary,
                        actionLabel = "Simpan",
                        onAction = { vm.resolvePending(item.target, approve = true) },
                        secondaryLabel = "Tolak",
                        onSecondary = { vm.resolvePending(item.target, approve = false) },
                    )
                }

                if (limitHit != null) {
                    Banner(
                        text = "Batas putaran tool tercapai.",
                        tone = MaterialTheme.colorScheme.tertiary,
                        actionLabel = "Lanjut",
                        onAction = vm::continueTurn,
                        secondaryLabel = "Prompt baru",
                        onSecondary = { vm.dismissLimit(); vm.newSession() },
                    )
                }

                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            status.ifBlank { "Berjalan..." },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = vm::stopTurn) { Text("Stop") }
                    }
                }

                Composer(
                    value = draft,
                    onValueChange = { draft = it },
                    busy = busy,
                    onSend = { vm.send(draft); draft = "" },
                )
            }

            if (showWorkdir) WorkdirDialog(vm, workdir, onDismiss = { showWorkdir = false })
            if (showSessions) SessionDialog(vm, onDismiss = { showSessions = false })
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, start = Spacing.xl, end = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text("NovaAgent", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Coding agent di Android lewat Termux. Ketik /help untuk daftar command.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Composer: rounded surface, grows to 6 lines, send button dims when unusable. */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
) {
    val enabled = !busy && value.isNotBlank()
    val sendAlpha by animateFloatAsState(if (enabled) 1f else 0.35f, label = "sendAlpha")

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        "Tanya atau ketik /help",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier
                    .size(44.dp)
                    .alpha(sendAlpha)
                    .clickable(enabled = enabled) { onSend() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("↑", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** One-line banner with up to two actions: errors, memory confirmation, round cap. */
@Composable
private fun Banner(
    text: String,
    tone: Color,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .background(tone.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .padding(start = Spacing.md, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = Spacing.sm),
        )
        secondaryLabel?.let { label ->
            TextButton(onClick = { onSecondary?.invoke() }) { Text(label) }
        }
        TextButton(onClick = onAction) { Text(actionLabel, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun WorkdirDialog(vm: ChatViewModel, current: String, onDismiss: () -> Unit) {
    var manual by remember { mutableStateOf(vm.defaultWorkdir()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workdir") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Sesi ini", style = MaterialTheme.typography.labelMedium)
                Text(current, style = monoStyle(12), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Folder per sesi dibuat otomatis oleh agent di dalam default workdir " +
                        "saat pertama kali menulis file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("Default workdir", style = MaterialTheme.typography.labelMedium)
                BasicTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    singleLine = true,
                    textStyle = monoStyle(13).copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                        .padding(Spacing.md),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.setDefaultWorkdir(manual); onDismiss() }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
private fun SessionDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    val sessions by vm.sessions.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pilih sesi") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(sessions, key = { it.id }) { s ->
                    TextButton(
                        onClick = { vm.openSession(s.id); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            s.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (s.id == vm.sessionId) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.newSession(); onDismiss() }) { Text("Sesi baru") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

/** Collapsible tool rows. Row toggles the result; the thought line toggles the reasoning. */
@Composable
private fun ToolRows(rounds: List<UiRound>) {
    Column(
        Modifier.padding(start = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rounds.forEach { r -> ToolRow(r) }
    }
}

@Composable
private fun ToolRow(r: UiRound) {
    var showResult by remember(r) { mutableStateOf(false) }
    var showThought by remember(r) { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (showResult) 90f else 0f, label = "chevron")

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { showResult = !showResult },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ">",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevron),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                "${r.name}: ${r.detail}",
                style = monoStyle(12),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (showResult) 4 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (r.thought.isNotBlank()) {
            Text(
                if (showThought) r.thought else "… ${r.thought.lineSequence().first().take(90)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (showThought) 12 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showThought = !showThought }
                    .padding(top = 2.dp),
            )
        }
        AnimatedVisibility(
            visible = showResult,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SelectionContainer {
                Text(
                    r.result,
                    style = monoStyle(11),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                )
            }
        }
    }
}

/** User turns are tinted bubbles on the right; assistant turns run full width, like Claude. */
@Composable
private fun Bubble(m: UiMessage) {
    if (m.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp))
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
            ) {
                SelectionContainer {
                    Text(
                        m.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    } else {
        MarkdownText(m.text, Modifier.fillMaxWidth())
    }
}
