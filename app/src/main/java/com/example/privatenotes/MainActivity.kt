package com.example.privatenotes

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

/** Lock again after the app has been out of sight for this long. Set to 0 to lock immediately. */
private const val AUTO_LOCK_MS = 15_000L

/** Days a deleted note stays in Trash before it is erased for good. */
const val TRASH_DAYS = 30

/** Longer grace period while the system file picker (export/import) is open. */
private const val PICKER_GRACE_MS = 120_000L

enum class Phase { Locked, Unlocked, Broken }

enum class SortMode(val label: String) {
    NewestEdited("Newest edited"),
    OldestEdited("Oldest edited"),
    TitleAZ("Title A–Z"),
    TitleZA("Title Z–A")
}

fun List<Note>.sortedFor(mode: SortMode): List<Note> = when (mode) {
    SortMode.NewestEdited -> sortedByDescending { it.updatedAt }
    SortMode.OldestEdited -> sortedBy { it.updatedAt }
    SortMode.TitleAZ -> sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER) { n: Note -> n.title.ifBlank { "Untitled" } }
    )
    SortMode.TitleZA -> sortedWith(
        compareByDescending(String.CASE_INSENSITIVE_ORDER) { n: Note -> n.title.ifBlank { "Untitled" } }
    )
}

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    var phase by mutableStateOf(Phase.Locked)
    var error by mutableStateOf<String?>(null)
    var openNoteId by mutableStateOf<String?>(null)
    val notes = mutableStateListOf<Note>()
    var busy by mutableStateOf(false)
    var hasBackupPassword by mutableStateOf(false)
    var sortMode by mutableStateOf(SortMode.NewestEdited)
    var exportDialog by mutableStateOf(false)
    var importUri by mutableStateOf<Uri?>(null)
    var message by mutableStateOf<String?>(null)
    var showTrash by mutableStateOf(false)
    var undoIds by mutableStateOf<List<String>>(emptyList())
    var selection by mutableStateOf<Set<String>>(emptySet())

    private var dek: ByteArray? = null
    private var saveJob: Job? = null
    private var pendingExportPw: CharArray? = null
    private val prefs get() = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val ctx get() = getApplication<Application>()

    init {
        hasBackupPassword = Vault.hasBackupPassword(ctx)
        sortMode = runCatching { SortMode.valueOf(prefs.getString("sort", "") ?: "") }
            .getOrDefault(SortMode.NewestEdited)
    }

    fun changeSort(mode: SortMode) {
        sortMode = mode
        prefs.edit().putString("sort", mode.name).apply()
    }

    // ---- Export / import ----

    /** Remember the export password until the user has picked where to save the file. */
    fun stageExport(pw: CharArray) {
        pendingExportPw?.fill('\u0000')
        pendingExportPw = pw
    }

    fun finishExport(uri: Uri?) {
        val pw = pendingExportPw
        pendingExportPw = null
        if (pw == null) return
        if (uri == null || phase != Phase.Unlocked) {
            pw.fill('\u0000')
            return
        }
        val snapshot = notes.filter { it.deletedAt == 0L }
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = Vault.exportNotes(snapshot, pw)
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: throw IOException("Could not open the file for writing")
                }
                message = "Exported ${snapshot.size} note(s). Keep the export password: you'll need it to import them."
            } catch (e: Exception) {
                message = "Export failed: ${e.message}"
            } finally {
                pw.fill('\u0000')
                busy = false
            }
        }
    }

    fun onImportFileChosen(uri: Uri?) {
        if (uri != null && phase == Phase.Unlocked) {
            error = null
            importUri = uri
        }
    }

    fun finishImport(pw: CharArray) {
        val uri = importUri ?: return
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("Could not open the file")
                    require(bytes.size <= 20_000_000) { "That file is too large to be a notes export." }
                    Vault.importNotes(bytes, pw)
                }
                if (phase == Phase.Unlocked) {
                    var added = 0
                    var updated = 0
                    var skipped = 0
                    for (n in imported) {
                        val i = notes.indexOfFirst { it.id == n.id }
                        when {
                            i < 0 -> { notes.add(n); added++ }
                            n.updatedAt > notes[i].updatedAt -> { notes[i] = n; updated++ }
                            else -> skipped++
                        }
                    }
                    saveNow()
                    importUri = null
                    message = "Import finished: $added added, $updated updated, $skipped already up to date."
                }
            } catch (e: AEADBadTagException) {
                error = "Wrong password, or the file is damaged."
            } catch (e: IllegalArgumentException) {
                error = e.message
            } catch (e: Exception) {
                error = "Import failed: ${e.message}"
            } finally {
                pw.fill('\u0000')
                busy = false
            }
        }
    }

    fun setBackupPassword(pw: CharArray, done: () -> Unit) {
        val key = dek?.copyOf() ?: return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { Vault.setBackupPassword(ctx, key, pw) }
                hasBackupPassword = true
                done()
            } catch (e: Exception) {
                error = "Could not save the password: ${e.message}"
            } finally {
                key.fill(0)
                pw.fill('\u0000')
                busy = false
            }
        }
    }

    fun onUnlocked(key: ByteArray) {
        try {
            val loaded = Vault.loadNotes(ctx, key)
            val cutoff = System.currentTimeMillis() - TRASH_DAYS * 86_400_000L
            val kept = loaded.filter { it.deletedAt == 0L || it.deletedAt > cutoff }
            notes.clear()
            notes.addAll(kept)
            dek = key
            if (kept.size != loaded.size) saveNow() // erase notes that sat in Trash too long
            error = null
            phase = Phase.Unlocked
        } catch (e: Exception) {
            key.fill(0)
            error = "Could not decrypt the notes file: ${e.message}"
        }
    }

    fun newNote(isChecklist: Boolean = false) {
        val items = if (isChecklist) listOf(CheckItem(UUID.randomUUID().toString(), "", false)) else emptyList()
        val n = Note(
            id = UUID.randomUUID().toString(),
            title = "",
            body = "",
            updatedAt = System.currentTimeMillis(),
            isChecklist = isChecklist,
            items = items
        )
        notes.add(n)
        openNoteId = n.id
    }

    private fun isBlankNote(n: Note) =
        n.title.isBlank() && if (n.isChecklist) n.items.all { it.text.isBlank() } else n.body.isBlank()

    /** Drops empty checklist rows, and removes the note entirely if nothing was written. */
    private fun tidy(id: String?) {
        if (id == null) return
        val i = notes.indexOfFirst { it.id == id }
        if (i < 0) return
        var n = notes[i]
        if (n.isChecklist && n.items.any { it.text.isBlank() }) {
            n = n.copy(items = n.items.filter { it.text.isNotBlank() })
            notes[i] = n
        }
        if (isBlankNote(n)) notes.removeAt(i)
    }

    private fun modify(id: String, block: (Note) -> Note) {
        val i = notes.indexOfFirst { it.id == id }
        if (i < 0) return
        notes[i] = block(notes[i]).copy(updatedAt = System.currentTimeMillis())
        scheduleSave()
    }

    fun setTitle(id: String, title: String) = modify(id) { it.copy(title = title) }

    fun setBody(id: String, body: String) = modify(id) { it.copy(body = body) }

    /** Adds an empty checklist row (after [afterItemId], or at the end) and returns its id. */
    fun addItem(noteId: String, afterItemId: String? = null): String {
        val item = CheckItem(UUID.randomUUID().toString(), "", false)
        modify(noteId) { n ->
            val list = n.items.toMutableList()
            val idx = if (afterItemId == null) -1 else list.indexOfFirst { it.id == afterItemId }
            if (idx < 0) list.add(item) else list.add(idx + 1, item)
            n.copy(items = list)
        }
        return item.id
    }

    fun setItemText(noteId: String, itemId: String, text: String) = modify(noteId) { n ->
        n.copy(items = n.items.map { if (it.id == itemId) it.copy(text = text) else it })
    }

    fun toggleItem(noteId: String, itemId: String) = modify(noteId) { n ->
        n.copy(items = n.items.map { if (it.id == itemId) it.copy(checked = !it.checked) else it })
    }

    fun removeItem(noteId: String, itemId: String) = modify(noteId) { n ->
        n.copy(items = n.items.filter { it.id != itemId })
    }

    // ---- Selection in the main list ----

    fun toggleSelect(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun clearSelection() {
        selection = emptySet()
    }

    /** Pins the selected notes, or unpins them if they are all already pinned. */
    fun pinSelected() {
        val chosen = notes.filter { it.id in selection }
        val pin = chosen.any { !it.pinned }
        for (n in chosen) {
            val i = notes.indexOfFirst { it.id == n.id }
            if (i >= 0) notes[i] = notes[i].copy(pinned = pin)
        }
        selection = emptySet()
        saveNow()
    }

    fun deleteSelected() {
        val ids = selection.toList()
        val now = System.currentTimeMillis()
        for (id in ids) {
            val i = notes.indexOfFirst { it.id == id }
            if (i >= 0) notes[i] = notes[i].copy(deletedAt = now)
        }
        undoIds = ids
        selection = emptySet()
        saveNow()
    }

    fun restoreNotes(ids: List<String>) {
        for (id in ids) {
            val i = notes.indexOfFirst { it.id == id }
            if (i >= 0) notes[i] = notes[i].copy(deletedAt = 0L)
        }
        saveNow()
    }

    fun update(id: String, title: String, body: String) {
        val i = notes.indexOfFirst { it.id == id }
        if (i < 0) return
        notes[i] = notes[i].copy(title = title, body = body, updatedAt = System.currentTimeMillis())
        scheduleSave()
    }

    fun closeNote() {
        val id = openNoteId
        openNoteId = null
        tidy(id)
        saveNow()
    }

    /** Moves a note to Trash (recoverable for TRASH_DAYS days). */
    fun deleteNote(id: String) {
        val i = notes.indexOfFirst { it.id == id }
        if (i >= 0) notes[i] = notes[i].copy(deletedAt = System.currentTimeMillis())
        openNoteId = null
        undoIds = listOf(id)
        saveNow()
    }

    fun restoreNote(id: String) {
        val i = notes.indexOfFirst { it.id == id }
        if (i >= 0) notes[i] = notes[i].copy(deletedAt = 0L)
        saveNow()
    }

    fun deleteForever(id: String) {
        notes.removeAll { it.id == id }
        saveNow()
    }

    fun emptyTrash() {
        notes.removeAll { it.deletedAt > 0L }
        saveNow()
    }

    fun togglePin(id: String) {
        val i = notes.indexOfFirst { it.id == id }
        if (i < 0) return
        notes[i] = notes[i].copy(pinned = !notes[i].pinned)
        scheduleSave()
    }

    // Notes are tiny, so saving on the main thread is a few milliseconds and avoids races.
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            saveNow()
        }
    }

    private fun saveNow() {
        saveJob?.cancel()
        val key = dek ?: return
        Vault.saveNotes(ctx, key, notes.toList())
    }

    fun lock() {
        if (phase == Phase.Unlocked) {
            tidy(openNoteId)
            saveNow()
        }
        saveJob?.cancel()
        pendingExportPw?.fill('\u0000')
        pendingExportPw = null
        exportDialog = false
        importUri = null
        message = null
        showTrash = false
        undoIds = emptyList()
        selection = emptySet()
        dek?.fill(0)
        dek = null
        notes.clear()
        openNoteId = null
        if (phase != Phase.Broken) phase = Phase.Locked
    }

    fun eraseEverything() {
        Vault.eraseAll(ctx)
        dek?.fill(0)
        dek = null
        notes.clear()
        openNoteId = null
        error = null
        hasBackupPassword = false
        phase = Phase.Locked
    }

    override fun onCleared() {
        dek?.fill(0)
    }
}

class MainActivity : FragmentActivity() {
    private val vm: VaultViewModel by viewModels()

    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        pickerOpen = false
        vm.finishExport(uri)
    }
    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        pickerOpen = false
        vm.onImportFileChosen(uri)
    }
    private lateinit var prompt: BiometricPrompt
    private var pendingSuccess: ((Cipher) -> Unit)? = null
    private var pendingDek: ByteArray? = null
    private var promptShowing = false
    private var pickerOpen = false
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hides notes in the recent-apps preview and blocks screenshots/screen recording.
        // Delete this line if you want to be able to take screenshots.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()

        prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    val cipher = result.cryptoObject?.cipher
                    if (cipher == null) {
                        vm.error = "Authentication did not return a key."
                        return
                    }
                    try {
                        pendingSuccess?.invoke(cipher)
                    } catch (e: Exception) {
                        vm.error = "Unlock failed: ${e.message}"
                    }
                    pendingSuccess = null
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptShowing = false
                    pendingSuccess = null
                    pendingDek?.fill(0)
                    pendingDek = null
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        vm.error = errString.toString()
                    }
                }
            }
        )

        setContent {
            PrivateNotesTheme {
                App(
                    vm = vm,
                    onUnlock = ::startUnlock,
                    onPassword = ::unlockWithPassword,
                    onExportPick = ::pickExportLocation,
                    onImportPick = ::pickImportFile
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (vm.phase == Phase.Unlocked &&
            SystemClock.elapsedRealtime() - backgroundedAt >
            (if (pickerOpen) PICKER_GRACE_MS else AUTO_LOCK_MS)
        ) {
            vm.lock()
        }
        if (vm.phase == Phase.Locked) startUnlock()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
        if (AUTO_LOCK_MS == 0L && !isChangingConfigurations) vm.lock()
    }

    private fun pickExportLocation() {
        pickerOpen = true
        createDoc.launch("PrivateNotes-export.pnotes")
    }

    private fun pickImportFile() {
        pickerOpen = true
        openDoc.launch(arrayOf("*/*"))
    }

    private fun startUnlock() {
        if (promptShowing) return
        val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators)
            != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            vm.error = "Set a screen lock (PIN, pattern, password or fingerprint) in Android " +
                    "Settings first, then reopen the app."
            return
        }
        try {
            if (Vault.isBroken(this)) {
                vm.phase = Phase.Broken
                return
            }
            if (Vault.isSetUp(this)) {
                val cipher = Vault.decryptCipher(this)
                authenticate("Unlock your notes", cipher) { vm.onUnlocked(Vault.finishUnlock(this, it)) }
            } else {
                Vault.createMasterKey()
                authenticate("Create your private notes vault", Vault.encryptCipher()) {
                    vm.onUnlocked(Vault.finishSetup(this, it))
                }
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            vm.phase = Phase.Broken
        } catch (e: Exception) {
            vm.error = "Could not start unlock: ${e.message}"
        }
    }

    /** Backup-password unlock. Also rebuilds the Keystore key if Android deleted the old one. */
    private fun unlockWithPassword(pw: CharArray) {
        if (vm.busy) return
        vm.busy = true
        vm.error = null
        lifecycleScope.launch {
            try {
                val dek = withContext(Dispatchers.Default) { Vault.unlockWithPassword(this@MainActivity, pw) }
                if (Vault.isBroken(this@MainActivity) || vm.phase == Phase.Broken) {
                    // Re-protect the data key with a new Keystore key under the current phone lock.
                    try {
                        Vault.createMasterKey()
                        pendingDek = dek
                        authenticate("Re-protect your notes with your screen lock", Vault.encryptCipher()) { c ->
                            Vault.rewrap(this@MainActivity, c, dek)
                            pendingDek = null
                            vm.onUnlocked(dek)
                        }
                    } catch (e: Exception) {
                        dek.fill(0)
                        vm.error = "Password accepted, but a new key couldn't be created. Make sure a " +
                                "screen lock (PIN, pattern or password) is set in Android Settings, then try again."
                    }
                } else {
                    vm.onUnlocked(dek)
                }
            } catch (e: AEADBadTagException) {
                vm.error = "Wrong password."
            } catch (e: Exception) {
                vm.error = "Could not unlock: ${e.message}"
            } finally {
                pw.fill('\u0000')
                vm.busy = false
            }
        }
    }

    private fun authenticate(title: String, cipher: Cipher, onSuccess: (Cipher) -> Unit) {
        pendingSuccess = onSuccess
        promptShowing = true
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}