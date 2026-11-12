package com.example.privatenotes

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

// ============================================================================================
// Theme
// ============================================================================================

@Composable
fun PrivateNotesTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(32.dp)
        ),
        content = content
    )
}

// ============================================================================================
// App shell
// ============================================================================================

@Composable
fun App(
    vm: VaultViewModel,
    onUnlock: () -> Unit,
    onPassword: (CharArray) -> Unit,
    onExportPick: () -> Unit,
    onImportPick: () -> Unit
) {
    var showEntry by remember { mutableStateOf(false) }
    LaunchedEffect(vm.phase) { if (vm.phase == Phase.Unlocked) showEntry = false }

    when (vm.phase) {
        Phase.Locked -> LockScreen(
            error = vm.error,
            hasPassword = vm.hasBackupPassword,
            onUnlock = onUnlock,
            onUsePassword = { vm.error = null; showEntry = true }
        )
        Phase.Broken -> BrokenScreen(
            hasPassword = vm.hasBackupPassword,
            onUsePassword = { vm.error = null; showEntry = true },
            onErase = vm::eraseEverything
        )
        Phase.Unlocked -> {
            val route = when {
                vm.openNoteId != null -> "editor"
                vm.showTrash -> "trash"
                else -> "list"
            }
            Crossfade(targetState = route, label = "screen") { r ->
                when (r) {
                    "editor" -> {
                        val id = vm.openNoteId
                        if (id != null) Editor(vm, id)
                    }
                    "trash" -> TrashScreen(vm)
                    else -> NotesList(vm, onExportPick, onImportPick)
                }
            }
        }
    }
    if (showEntry && vm.phase != Phase.Unlocked) {
        PasswordEntryDialog(vm, onSubmit = onPassword, onDismiss = { showEntry = false })
    }
}

@Composable
fun LockScreen(
    error: String?,
    hasPassword: Boolean,
    onUnlock: () -> Unit,
    onUsePassword: () -> Unit
) {
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(104.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Private Notes",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Encrypted and locked",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Unlock")
            }
            if (hasPassword) {
                TextButton(onClick = onUsePassword, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Use backup password")
                }
            }
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
fun BrokenScreen(hasPassword: Boolean, onUsePassword: () -> Unit, onErase: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Vault key is gone", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Android deleted the encryption key, usually because the phone's screen lock was " +
                        "removed or changed. " + if (hasPassword) {
                    "Your notes can be recovered with your backup password."
                } else {
                    "No backup password was set, so the old notes can't be decrypted any more."
                },
                modifier = Modifier.padding(vertical = 16.dp)
            )
            if (hasPassword) {
                Button(onClick = onUsePassword) { Text("Recover with backup password") }
            }
            TextButton(onClick = { confirm = true }) { Text("Erase and start over") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Erase vault?") },
            text = { Text("All existing notes are permanently lost.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onErase() }) { Text("Erase") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

// ============================================================================================
// Notes list
// ============================================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesList(vm: VaultViewModel, onExportPick: () -> Unit, onImportPick: () -> Unit) {
    var showSetPassword by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf(0) } // 0 all, 1 notes, 2 checklists
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val selecting = vm.selection.isNotEmpty()
    val dateFmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    BackHandler(enabled = selecting) { vm.clearSelection() }

    // "Moved to Trash" with an Undo button.
    LaunchedEffect(vm.undoIds) {
        val ids = vm.undoIds
        if (ids.isEmpty()) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = if (ids.size == 1) "Moved to Trash" else "Moved ${ids.size} notes to Trash",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) vm.restoreNotes(ids)
        if (vm.undoIds == ids) vm.undoIds = emptyList()
    }
    DisposableEffect(Unit) { onDispose { vm.undoIds = emptyList() } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                SelectionBar(vm)
            } else {
                LargeTopAppBar(
                    title = { Text("Notes", fontWeight = FontWeight.SemiBold) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(painterResource(R.drawable.ic_sort), contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (mode == vm.sortMode) "✓  ${mode.label}" else mode.label)
                                        },
                                        onClick = { vm.changeSort(mode); sortMenu = false }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = vm::lock) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock")
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Trash (${vm.notes.count { it.deletedAt > 0L }})") },
                                    onClick = { moreMenu = false; vm.showTrash = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export notes") },
                                    onClick = { moreMenu = false; vm.exportDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Import notes") },
                                    onClick = { moreMenu = false; onImportPick() }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (vm.hasBackupPassword) "Change backup password"
                                            else "Set backup password"
                                        )
                                    },
                                    onClick = { moreMenu = false; vm.error = null; showSetPassword = true }
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selecting) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { fabMenu = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("New") }
                    )
                    DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Note") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_notes), contentDescription = null) },
                            onClick = { fabMenu = false; vm.newNote(false) }
                        )
                        DropdownMenuItem(
                            text = { Text("Checklist") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_checklist), contentDescription = null) },
                            onClick = { fabMenu = false; vm.newNote(true) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        val q = query.trim()
        val active = vm.notes
            .filter { it.deletedAt == 0L }
            .filter { typeFilter == 0 || (typeFilter == 1 && !it.isChecklist) || (typeFilter == 2 && it.isChecklist) }
            .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
            .sortedFor(vm.sortMode)
        val pinned = active.filter { it.pinned }
        val others = active.filter { !it.pinned }
        val scheme = MaterialTheme.colorScheme

        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search titles") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = scheme.surfaceContainerHigh,
                    unfocusedContainerColor = scheme.surfaceContainerHigh,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = scheme.primary
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Notes", "Checklists").forEachIndexed { i, label ->
                    FilterChip(
                        selected = typeFilter == i,
                        onClick = { typeFilter = i },
                        label = { Text(label) }
                    )
                }
            }

            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 112.dp),
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!vm.hasBackupPassword) {
                    item(key = "banner", span = StaggeredGridItemSpan.FullLine) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = scheme.tertiaryContainer),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { vm.error = null; showSetPassword = true }
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = scheme.onTertiaryContainer)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "No backup password yet. Tap to set one, so you can recover your " +
                                            "notes if your phone's screen lock changes.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
                if (active.isEmpty()) {
                    item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_notes),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = scheme.onSurfaceVariant
                            )
                            Text(
                                if (q.isEmpty() && typeFilter == 0) "No notes yet" else "Nothing matches",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            Text(
                                if (q.isEmpty() && typeFilter == 0) "Tap New to add a note or a checklist."
                                else "Try a different title or filter.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (pinned.isNotEmpty()) {
                    item(key = "h-pinned", span = StaggeredGridItemSpan.FullLine) { SectionLabel("Pinned") }
                    items(pinned, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            selected = note.id in vm.selection,
                            dateFmt = dateFmt,
                            onClick = { if (selecting) vm.toggleSelect(note.id) else vm.openNoteId = note.id },
                            onLongClick = { vm.toggleSelect(note.id) }
                        )
                    }
                }
                if (pinned.isNotEmpty() && others.isNotEmpty()) {
                    item(key = "h-others", span = StaggeredGridItemSpan.FullLine) { SectionLabel("Others") }
                }
                items(others, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        selected = note.id in vm.selection,
                        dateFmt = dateFmt,
                        onClick = { if (selecting) vm.toggleSelect(note.id) else vm.openNoteId = note.id },
                        onLongClick = { vm.toggleSelect(note.id) }
                    )
                }
            }
        }
    }

    if (showSetPassword) SetPasswordDialog(vm, onDismiss = { showSetPassword = false })
    if (vm.exportDialog) ExportPasswordDialog(vm, onPick = onExportPick)
    if (vm.importUri != null) ImportPasswordDialog(vm)
    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(vm: VaultViewModel) {
    val chosen = vm.notes.filter { it.id in vm.selection }
    val allPinned = chosen.isNotEmpty() && chosen.all { it.pinned }
    TopAppBar(
        title = { Text("${vm.selection.size} selected") },
        navigationIcon = {
            IconButton(onClick = vm::clearSelection) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = vm::pinSelected) {
                Icon(
                    painterResource(R.drawable.ic_pin),
                    contentDescription = if (allPinned) "Unpin" else "Pin",
                    tint = if (allPinned) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
            IconButton(onClick = vm::deleteSelected) {
                Icon(Icons.Default.Delete, contentDescription = "Move to Trash")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: Note,
    selected: Boolean,
    dateFmt: DateFormat,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val container = when {
        selected -> scheme.primaryContainer
        note.pinned -> scheme.secondaryContainer
        else -> scheme.surfaceContainerHigh
    }
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (selected) BorderStroke(2.dp, scheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.isChecklist) {
                    Icon(
                        painterResource(R.drawable.ic_checklist),
                        contentDescription = "Checklist",
                        modifier = Modifier.size(18.dp),
                        tint = scheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    note.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = scheme.primary)
                } else if (note.pinned) {
                    Icon(
                        painterResource(R.drawable.ic_pin),
                        contentDescription = "Pinned",
                        modifier = Modifier.size(16.dp),
                        tint = scheme.onSecondaryContainer
                    )
                }
            }
            if (note.isChecklist) {
                val open = note.items.filter { !it.checked }
                open.take(4).forEach { item ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text("☐ ", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (open.size > 4) {
                    Text(
                        "+${open.size - 4} more",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (note.items.isNotEmpty()) {
                    Text(
                        "${note.items.count { it.checked }} of ${note.items.size} done",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else if (note.body.isNotBlank()) {
                Text(
                    note.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Text(
                dateFmt.format(Date(note.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

// ============================================================================================
// Editor (text notes and checklists)
// ============================================================================================

private fun Note.asPlainText(): String =
    if (isChecklist) items.joinToString("\n") { (if (it.checked) "[x] " else "[ ] ") + it.text } else body

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("note", text)
    if (Build.VERSION.SDK_INT >= 33) {
        // Tells Android to hide the copied text from the clipboard preview.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    } else {
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
    cm.setPrimaryClip(clip)
}

@Composable
private fun plainFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Editor(vm: VaultViewModel, id: String) {
    val note = vm.notes.firstOrNull { it.id == id } ?: return
    val context = LocalContext.current
    var title by remember(id) { mutableStateOf(note.title) }
    val focus = remember { FocusRequester() }
    val dateFmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    BackHandler { vm.closeNote() }
    LaunchedEffect(id) {
        val empty = note.title.isEmpty() &&
                (if (note.isChecklist) note.items.all { it.text.isEmpty() } else note.body.isEmpty())
        if (empty) focus.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = vm::closeNote) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.togglePin(id) }) {
                        Icon(
                            painterResource(R.drawable.ic_pin),
                            contentDescription = if (note.pinned) "Unpin" else "Pin",
                            tint = if (note.pinned) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { copyToClipboard(context, note.asPlainText()) }) {
                        Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy")
                    }
                    IconButton(onClick = { vm.deleteNote(id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Move to Trash")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 8.dp)) {
            TextField(
                value = title,
                onValueChange = { title = it; vm.setTitle(id, it) },
                placeholder = {
                    Text("Title", style = MaterialTheme.typography.headlineSmall)
                },
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                singleLine = true,
                colors = plainFieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            if (note.isChecklist) {
                ChecklistBody(vm, note, Modifier.weight(1f))
            } else {
                TextBody(vm, note, Modifier.weight(1f))
            }
            val footer = if (note.isChecklist) {
                "${note.items.count { it.checked }} of ${note.items.size} done"
            } else {
                val words = note.body.split(Regex("\\s+")).count { it.isNotEmpty() }
                "$words words · ${note.body.length} characters"
            }
            Text(
                "$footer · Edited ${dateFmt.format(Date(note.updatedAt))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun TextBody(vm: VaultViewModel, note: Note, modifier: Modifier) {
    var body by remember(note.id) { mutableStateOf(note.body) }
    TextField(
        value = body,
        onValueChange = { body = it; vm.setBody(note.id, it) },
        placeholder = { Text("Start typing…") },
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = plainFieldColors(),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun ChecklistBody(vm: VaultViewModel, note: Note, modifier: Modifier) {
    var checkedOpen by remember(note.id) { mutableStateOf(true) }
    var focusId by remember(note.id) { mutableStateOf<String?>(null) }
    val open = note.items.filter { !it.checked }
    val done = note.items.filter { it.checked }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(open, key = { it.id }) { item ->
            ChecklistRow(
                item = item,
                autoFocus = focusId == item.id,
                onTextChange = { vm.setItemText(note.id, item.id, it) },
                onToggle = { vm.toggleItem(note.id, item.id) },
                onDelete = { vm.removeItem(note.id, item.id) },
                onEnter = { focusId = vm.addItem(note.id, item.id) }
            )
        }
        item(key = "add-item") {
            TextButton(
                onClick = { focusId = vm.addItem(note.id) },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add item")
            }
        }
        if (done.isNotEmpty()) {
            item(key = "checked-header") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { checkedOpen = !checkedOpen }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Checked (${done.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (checkedOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (checkedOpen) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (checkedOpen) {
                items(done, key = { it.id }) { item ->
                    ChecklistRow(
                        item = item,
                        autoFocus = false,
                        onTextChange = { vm.setItemText(note.id, item.id, it) },
                        onToggle = { vm.toggleItem(note.id, item.id) },
                        onDelete = { vm.removeItem(note.id, item.id) },
                        onEnter = {}
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    item: CheckItem,
    autoFocus: Boolean,
    onTextChange: (String) -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEnter: () -> Unit
) {
    var text by remember(item.id) { mutableStateOf(item.text) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) focus.requestFocus() }

    val base = MaterialTheme.typography.bodyLarge
    val style = if (item.checked) {
        base.copy(
            textDecoration = TextDecoration.LineThrough,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else base

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = item.checked, onCheckedChange = { onToggle() })
        TextField(
            value = text,
            onValueChange = { v ->
                if (v.contains('\n')) {
                    // Pressing Enter starts the next item instead of adding a line break.
                    val cleaned = v.replace("\n", "")
                    text = cleaned
                    onTextChange(cleaned)
                    onEnter()
                } else {
                    text = v
                    onTextChange(v)
                }
            },
            placeholder = { Text("List item") },
            textStyle = style,
            colors = plainFieldColors(),
            modifier = Modifier.weight(1f).focusRequester(focus)
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Close, contentDescription = "Remove item")
        }
    }
}

// ============================================================================================
// Trash
// ============================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: VaultViewModel) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmForever by remember { mutableStateOf<String?>(null) }
    val trashed = vm.notes.filter { it.deletedAt > 0L }.sortedByDescending { it.deletedAt }
    val scheme = MaterialTheme.colorScheme

    BackHandler { vm.showTrash = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = {
                    IconButton(onClick = { vm.showTrash = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (trashed.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) { Text("Empty") }
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Deleted notes are erased for good after $TRASH_DAYS days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }
            if (trashed.isEmpty()) {
                item { Text("Trash is empty.", modifier = Modifier.padding(vertical = 16.dp)) }
            }
            items(trashed, key = { it.id }) { note ->
                val daysLeft =
                    (TRASH_DAYS - (System.currentTimeMillis() - note.deletedAt) / 86_400_000L)
                        .coerceAtLeast(0)
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            note.title.ifBlank { "Untitled" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val preview = note.asPlainText()
                        if (preview.isNotBlank()) {
                            Text(
                                preview,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Text(
                            "Deleted ${fmt.format(Date(note.deletedAt))} · $daysLeft days left",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Row {
                            TextButton(onClick = { vm.restoreNote(note.id) }) { Text("Restore") }
                            TextButton(onClick = { confirmForever = note.id }) { Text("Delete forever") }
                        }
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty Trash?") },
            text = { Text("All notes in Trash are permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; vm.emptyTrash() }) { Text("Empty") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } }
        )
    }
    confirmForever?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmForever = null },
            title = { Text("Delete forever?") },
            text = { Text("This note can't be recovered.") },
            confirmButton = {
                TextButton(onClick = { confirmForever = null; vm.deleteForever(id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmForever = null }) { Text("Cancel") } }
        )
    }
}

// ============================================================================================
// Password dialogs
// ============================================================================================

@Composable
fun PasswordEntryDialog(vm: VaultViewModel, onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!vm.busy) onDismiss() },
        title = { Text("Backup password") },
        text = {
            Column {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (vm.busy) Text("Checking…", modifier = Modifier.padding(top = 8.dp))
                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pw.isNotEmpty() && !vm.busy, onClick = { onSubmit(pw.toCharArray()) }) {
                Text("Unlock")
            }
        },
        dismissButton = { TextButton(enabled = !vm.busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SetPasswordDialog(vm: VaultViewModel, onDismiss: () -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val valid = p1.length >= 8 && p1 == p2
    AlertDialog(
        onDismissRequest = { if (!vm.busy) onDismiss() },
        title = { Text(if (vm.hasBackupPassword) "Change backup password" else "Set backup password") },
        text = {
            Column {
                Text(
                    "Lets you recover your notes if your phone's screen lock changes. At least 8 " +
                            "characters. Anyone with this password can open your notes, so make it " +
                            "strong and don't lose it."
                )
                OutlinedTextField(
                    value = p1,
                    onValueChange = { p1 = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                OutlinedTextField(
                    value = p2,
                    onValueChange = { p2 = it },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (p2.isNotEmpty() && p1 != p2) {
                    Text(
                        "Passwords don't match.", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (vm.busy) Text("Saving…", modifier = Modifier.padding(top = 8.dp))
                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !vm.busy,
                onClick = { vm.setBackupPassword(p1.toCharArray(), onDismiss) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(enabled = !vm.busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ExportPasswordDialog(vm: VaultViewModel, onPick: () -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val valid = p1.length >= 8 && p1 == p2
    AlertDialog(
        onDismissRequest = { vm.exportDialog = false },
        title = { Text("Export notes") },
        text = {
            Column {
                Text(
                    "Choose a password for the export file. You'll need it to import the notes on " +
                            "another phone. At least 8 characters. Use something strong, because the " +
                            "file can be copied anywhere."
                )
                OutlinedTextField(
                    value = p1,
                    onValueChange = { p1 = it },
                    label = { Text("Export password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                OutlinedTextField(
                    value = p2,
                    onValueChange = { p2 = it },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (p2.isNotEmpty() && p1 != p2) {
                    Text(
                        "Passwords don't match.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    vm.exportDialog = false
                    vm.stageExport(p1.toCharArray())
                    onPick()
                }
            ) { Text("Choose location") }
        },
        dismissButton = { TextButton(onClick = { vm.exportDialog = false }) { Text("Cancel") } }
    )
}

@Composable
fun ImportPasswordDialog(vm: VaultViewModel) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!vm.busy) vm.importUri = null },
        title = { Text("Import notes") },
        text = {
            Column {
                Text(
                    "Enter the password that was chosen when the file was exported. Notes are " +
                            "merged with your existing ones: new notes are added, and a note that " +
                            "exists in both is replaced only if the imported copy is newer."
                )
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Export password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                if (vm.busy) Text("Decrypting…", modifier = Modifier.padding(top = 8.dp))
                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pw.isNotEmpty() && !vm.busy,
                onClick = { vm.finishImport(pw.toCharArray()) }
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(enabled = !vm.busy, onClick = { vm.importUri = null }) { Text("Cancel") }
        }
    )
}