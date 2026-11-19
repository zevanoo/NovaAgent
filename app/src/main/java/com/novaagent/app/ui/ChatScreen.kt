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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
 * Chat surface.
 *
 * Insets are owned in exactly one place: the Scaffold consumes system bars and
 * the composer carries `windowInsetsPadding(WindowInsets.ime)` itself. The old
 * layout applied `imePadding()` to a Column that had already been inset by the
 * Scaffold, which counted the keyboard twice and left a full keyboard-height
 * gap above the composer while typing.
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
    var deleteTarget by remember { mutableStateOf<UiMessage?>(null) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    Modifier.fillMaxWidth().padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sesi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { vm.newSession(); scope.launch { drawer.close() } }) {
                        Icon(Icons.Default.Add, contentDescription = "Sesi baru")
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(sessions, key = { it.id }) { s ->
                        val selected = s.id == vm.sessionId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.openSession(s.id); scope.launch { drawer.close() } }
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                s.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                IconButton(
                                    onClick = { vm.deleteSession(s.id) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Hapus sesi",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
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
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    title = {
                        Column {
                            Text("NovaAgent", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "@ $workdir",
                                style = monoStyle(11),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { showWorkdir = true },
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Sesi")
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
        ) { inner ->
            // Scaffold's padding only carries the app bar: the content window is
            // told not to consume insets, and the composer claims the IME alone.
            Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = Spacing.md,
                        end = Spacing.md,
                        top = Spacing.sm,
                        bottom = Spacing.sm,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    if (messages.isEmpty()) item { EmptyState() }
                    items(messages, key = { it.id }) { m ->
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Bubble(
                                message = m,
                                onRetry = { vm.retry(m) },
                                onDelete = { deleteTarget = m },
                            )
                            if (m.rounds.isNotEmpty()) ToolRows(m.rounds)
                        }
                    }
                }

                error?.let { msg ->
                    Banner(
                        text = msg,
                        actionLabel = "Tutup",
                        onAction = vm::clearError,
                    )
                }

                pendingMemory.forEach { item ->
                    Banner(
                        text = "Simpan ke ${item.target}: ${item.note.take(70)}",
                        actionLabel = "Simpan",
                        onAction = { vm.resolvePending(item.target, approve = true) },
                        secondaryLabel = "Tolak",
                        onSecondary = { vm.resolvePending(item.target, approve = false) },
                    )
                }

                if (limitHit != null) {
                    Banner(
                        text = "Batas putaran tool tercapai.",
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
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            status.ifBlank { "Berjalan..." },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = vm::interruptTurn) { Text("Interrupt") }
                        TextButton(onClick = vm::stopTurn) { Text("Stop") }
                    }
                }

                Composer(
                    value = draft,
                    onValueChange = { draft = it },
                    busy = busy,
                    onSend = { vm.send(draft); draft = "" },
                    onStop = vm::stopTurn,
                )
            }
        }
    }

    if (showWorkdir) WorkdirDialog(vm, workdir, onDismiss = { showWorkdir = false })
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus pesan?") },
            text = { Text(target.text.take(120)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteMessage(target); deleteTarget = null }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Batal") } },
        )
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
            "Agent di Android. Ketik /help untuk daftar command.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Composer. Claims the IME inset and the navigation bar itself, so it sits right
 * on top of the keyboard and nothing else has to add padding for it.
 */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val enabled = !busy && value.isNotBlank()
    val sendAlpha by animateFloatAsState(if (enabled) 1f else 0.35f, label = "sendAlpha")

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .padding(vertical = Spacing.sm),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            "Tanya atau ketik /help",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
            Spacer(Modifier.width(Spacing.sm))
            FilledTonalIconButton(
                onClick = if (busy) onStop else onSend,
                enabled = busy || enabled,
                modifier = Modifier.size(44.dp).alpha(if (busy) 1f else sendAlpha),
            ) {
                Icon(
                    if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (busy) "Stop" else "Kirim",
                )
            }
        }
    }
}

/** Inline status row with up to two text actions. */
@Composable
private fun Banner(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
    ) {
        Row(
            Modifier.padding(start = Spacing.md, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.sm),
            )
            secondaryLabel?.let { TextButton(onClick = { onSecondary?.invoke() }) { Text(it) } }
            TextButton(onClick = onAction) { Text(actionLabel, fontWeight = FontWeight.SemiBold) }
        }
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

/** Collapsible tool rows. Row toggles the result; the thought line toggles the reasoning. */
@Composable
private fun ToolRows(rounds: List<UiRound>) {
    Column(
        Modifier.padding(start = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rounds.forEach { ToolRow(it) }
    }
}

@Composable
private fun ToolRow(r: UiRound) {
    var showResult by remember(r) { mutableStateOf(false) }
    var showThought by remember(r) { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (showResult) 90f else 0f, label = "chevron")

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(
                Modifier.fillMaxWidth().clickable { showResult = !showResult },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ">",
                    style = monoStyle(11),
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
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    )
                }
            }
        }
    }
}

/**
 * User turns are tinted bubbles on the right; assistant turns run full width.
 * Each bubble carries its own actions: retry re-runs that turn, delete drops it.
 */
@Composable
private fun Bubble(message: UiMessage, onRetry: () -> Unit, onDelete: () -> Unit) {
    var showActions by remember(message.id) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { showActions = !showActions },
    ) {
        if (message.role == "user") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .widthIn(max = 320.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(18.dp),
                        )
                        .padding(horizontal = Spacing.md, vertical = 10.dp),
                ) {
                    SelectionContainer {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        } else {
            MarkdownText(message.text, Modifier.fillMaxWidth())
        }

        AnimatedVisibility(visible = showActions, enter = fadeIn(), exit = fadeOut()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (message.role == "user") {
                    MessageAction("Retry", Icons.Default.Refresh, onRetry)
                }
                MessageAction("Hapus", Icons.Default.Delete, onDelete)
            }
        }
    }
}

@Composable
private fun MessageAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
