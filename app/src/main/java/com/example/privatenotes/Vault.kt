package com.example.privatenotes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class CheckItem(
    val id: String,
    val text: String,
    val checked: Boolean,
)

data class Note(
    val id: String,
    val title: String,
    val body: String,
    val updatedAt: Long,
    val pinned: Boolean = false,
    /** 0 = not deleted; otherwise the time it was moved to Trash. */
    val deletedAt: Long = 0L,
    /** A checklist note uses [items]; a normal note uses [body]. */
    val isChecklist: Boolean = false,
    val items: List<CheckItem> = emptyList(),
)

/**
 * How the encryption works (envelope encryption):
 *
 *  1. A master key lives inside the Android Keystore (hardware-backed, StrongBox/Titan M2 on a
 *     Pixel 7 when available). It can only be used right after you authenticate with your
 *     fingerprint / face / phone PIN / pattern / password (per-use authentication).
 *  2. That master key encrypts a random 256-bit "data key", stored in vault.key.
 *  3. The data key encrypts all notes (AES-256-GCM) into notes.enc.
 *
 * Without a successful phone-lock authentication the data key can't be unwrapped, so the notes
 * file is unreadable noise. The data key is held in RAM only while the app is unlocked.
 */
object Vault {
    private const val ALIAS = "private_notes_master_key"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val KEY_FILE = "vault.key"
    private const val NOTES_FILE = "notes.enc"
    private const val PW_FILE = "vault.pw"
    private const val SALT_BYTES = 16
    private const val PBKDF2_ITERATIONS = 300_000

    private fun itemsToJson(items: List<CheckItem>): JSONArray {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("t", it.text).put("c", it.checked)) }
        return a
    }

    private fun itemsFromJson(a: JSONArray?): List<CheckItem> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            CheckItem(o.getString("id"), o.getString("t"), o.optBoolean("c", false))
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun keyFile(ctx: Context) = File(ctx.filesDir, KEY_FILE)
    private fun notesFile(ctx: Context) = File(ctx.filesDir, NOTES_FILE)
    private fun pwFile(ctx: Context) = File(ctx.filesDir, PW_FILE)

    fun isSetUp(ctx: Context): Boolean =
        keyFile(ctx).exists() && keyStore().containsAlias(ALIAS)

    /** Vault files exist but the Keystore key is gone (e.g. the phone's screen lock was removed). */
    fun isBroken(ctx: Context): Boolean =
        keyFile(ctx).exists() && !keyStore().containsAlias(ALIAS)

    fun createMasterKey() {
        keyStore().deleteEntry(ALIAS)

        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // timeout 0 = authentication required for every single use of the key
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
            // Don't destroy the vault if you add/remove a fingerprint later.
            .setInvalidatedByBiometricEnrollment(false)
            .setIsStrongBoxBacked(strongBox)
            .build()

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        try {
            gen.init(spec(true))
            gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            gen.init(spec(false))
            gen.generateKey()
        }
    }

    private fun masterKey(): SecretKey = keyStore().getKey(ALIAS, null) as SecretKey

    /** Cipher to hand to the biometric prompt on first run. */
    fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, masterKey()) }

    /** Cipher to hand to the biometric prompt on every unlock. */
    fun decryptCipher(ctx: Context): Cipher {
        val blob = keyFile(ctx).readBytes()
        val iv = blob.copyOfRange(0, IV_BYTES)
        return Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
        }
    }

    /** Called with the cipher returned by a successful authentication, first run. */
    fun finishSetup(ctx: Context, authedCipher: Cipher): ByteArray {
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = authedCipher.doFinal(dek)
        writeAtomic(keyFile(ctx), authedCipher.iv + wrapped)
        saveNotes(ctx, dek, emptyList())
        return dek
    }

    /** Called with the cipher returned by a successful authentication, every unlock. */
    fun finishUnlock(ctx: Context, authedCipher: Cipher): ByteArray {
        val blob = keyFile(ctx).readBytes()
        return authedCipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    @Synchronized
    fun saveNotes(ctx: Context, dek: ByteArray, notes: List<Note>) {
        val arr = JSONArray()
        notes.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("t", it.title)
                    .put("b", it.body)
                    .put("u", it.updatedAt)
                    .put("p", it.pinned)
                    .put("d", it.deletedAt)
                    .put("k", it.isChecklist)
                    .put("i", itemsToJson(it.items))
            )
        }
        val plain = arr.toString().toByteArray(Charsets.UTF_8)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"))
        val out = c.iv + c.doFinal(plain)
        plain.fill(0)
        writeAtomic(notesFile(ctx), out)
    }

    @Synchronized
    fun loadNotes(ctx: Context, dek: ByteArray): List<Note> {
        val f = notesFile(ctx)
        if (!f.exists()) return emptyList()
        val blob = f.readBytes()
        val c = Cipher.getInstance(TRANSFORM)
        c.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(dek, "AES"),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES)
        )
        val plain = c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        val arr = JSONArray(String(plain, Charsets.UTF_8))
        plain.fill(0)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Note(
                o.getString("id"), o.getString("t"), o.getString("b"), o.getLong("u"),
                o.optBoolean("p", false), o.optLong("d", 0L),
                o.optBoolean("k", false), itemsFromJson(o.optJSONArray("i"))
            )
        }
    }

    // ---- Backup password -------------------------------------------------------------------
    // A second, independent copy of the data key, wrapped with a key derived from a password you
    // choose (PBKDF2-HMAC-SHA256 + AES-256-GCM). It lets you get back in if the phone's screen
    // lock changes and Android deletes the Keystore key. Anyone who knows this password can open
    // the notes, so make it strong.

    fun hasBackupPassword(ctx: Context): Boolean = pwFile(ctx).exists()

    private fun passwordKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Slow on purpose (about a second). Call off the main thread. */
    fun setBackupPassword(ctx: Context, dek: ByteArray, password: CharArray) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, passwordKey(password, salt))
        writeAtomic(pwFile(ctx), salt + c.iv + c.doFinal(dek))
    }

    /** Throws javax.crypto.AEADBadTagException on a wrong password. Call off the main thread. */
    fun unlockWithPassword(ctx: Context, password: CharArray): ByteArray {
        val f = pwFile(ctx)
        check(f.exists()) { "No backup password has been set" }
        val blob = f.readBytes()
        val salt = blob.copyOfRange(0, SALT_BYTES)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(
            Cipher.DECRYPT_MODE,
            passwordKey(password, salt),
            GCMParameterSpec(TAG_BITS, blob, SALT_BYTES, IV_BYTES)
        )
        val start = SALT_BYTES + IV_BYTES
        return c.doFinal(blob, start, blob.size - start)
    }

    /** After recovering with the password: protect the data key with a fresh Keystore key. */
    fun rewrap(ctx: Context, authedCipher: Cipher, dek: ByteArray) {
        writeAtomic(keyFile(ctx), authedCipher.iv + authedCipher.doFinal(dek))
    }

    // ---- Export / import -------------------------------------------------------------------
    // File layout: "PNOTES1" | salt(16) | iv(12) | AES-256-GCM ciphertext of a JSON document.
    // The key comes from a password you pick at export time (PBKDF2), so the file can be opened
    // on any phone that has this app, without needing this phone's Keystore.

    private val EXPORT_MAGIC = "PNOTES1".toByteArray(Charsets.US_ASCII)

    /** Slow on purpose (about a second). Call off the main thread. */
    fun exportNotes(notes: List<Note>, password: CharArray): ByteArray {
        val arr = JSONArray()
        notes.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("body", it.body)
                    .put("updatedAt", it.updatedAt)
                    .put("pinned", it.pinned)
                    .put("checklist", it.isChecklist)
                    .put("items", itemsToJson(it.items))
            )
        }
        val doc = JSONObject()
            .put("format", "privatenotes")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("notes", arr)
        val plain = doc.toString().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, passwordKey(password, salt))
        c.updateAAD(EXPORT_MAGIC)
        val out = EXPORT_MAGIC + salt + c.iv + c.doFinal(plain)
        plain.fill(0)
        return out
    }

    /**
     * Throws javax.crypto.AEADBadTagException on a wrong password and IllegalArgumentException
     * if the file isn't an export. Call off the main thread.
     */
    fun importNotes(file: ByteArray, password: CharArray): List<Note> {
        val header = EXPORT_MAGIC.size
        val start = header + SALT_BYTES + IV_BYTES
        require(file.size > start + 16 && file.copyOfRange(0, header).contentEquals(EXPORT_MAGIC)) {
            "This isn't a Private Notes export file."
        }
        val salt = file.copyOfRange(header, header + SALT_BYTES)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(
            Cipher.DECRYPT_MODE,
            passwordKey(password, salt),
            GCMParameterSpec(TAG_BITS, file, header + SALT_BYTES, IV_BYTES)
        )
        c.updateAAD(EXPORT_MAGIC)
        val plain = c.doFinal(file, start, file.size - start)
        try {
            val doc = JSONObject(String(plain, Charsets.UTF_8))
            require(doc.optString("format") == "privatenotes") { "This isn't a Private Notes export file." }
            require(doc.optInt("version", 0) in 1..1) { "This export was made by a newer version of the app." }
            val arr = doc.getJSONArray("notes")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Note(
                    o.getString("id"), o.getString("title"), o.getString("body"),
                    o.getLong("updatedAt"), o.optBoolean("pinned", false), 0L,
                    o.optBoolean("checklist", false), itemsFromJson(o.optJSONArray("items"))
                )
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Wipes the key and all data. Irreversible. */
    @Synchronized
    fun eraseAll(ctx: Context) {
        keyFile(ctx).delete()
        notesFile(ctx).delete()
        pwFile(ctx).delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun writeAtomic(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(data)
        Files.move(
            tmp.toPath(), target.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
        )
    }
}