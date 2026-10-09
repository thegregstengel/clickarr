package net.clickarr.core.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.clickarr.core.common.Log

/**
 * Small encrypted key-value store for credentials (proposal 16.2, ADR 0014).
 *
 * Values are serialized to one JSON document, encrypted with AES-256-GCM under a key that lives in the
 * Android Keystore and cannot leave the device, and written atomically to app-private storage. A fresh
 * random IV per write is stored in front of the ciphertext. If the Keystore is unusable on a device, a
 * software key in app-private storage is used instead and the condition is logged for Diagnostics.
 *
 * Never put anything here that should be synchronized; the household protocol does not read this store.
 */
class SecretStore(context: Context, fileName: String = "secrets.bin") {
    private val file = File(context.applicationContext.noBackupFilesDir, fileName)
    private val fallbackKeyFile = File(context.applicationContext.noBackupFilesDir, "$fileName.key")
    private val mutex = Mutex()
    private val json = Json
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    @Volatile
    var usingSoftwareKey: Boolean = false
        private set

    suspend fun get(key: String): String? = mutex.withLock { readAll()[key] }

    suspend fun put(key: String, value: String) = mutex.withLock { writeAll(readAll() + (key to value)) }

    suspend fun remove(key: String) = mutex.withLock { writeAll(readAll() - key) }

    suspend fun keys(): Set<String> = mutex.withLock { readAll().keys }

    suspend fun clear() = mutex.withLock { writeAll(emptyMap()) }

    private suspend fun readAll(): Map<String, String> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyMap()
        val bytes = file.readBytes()
        if (bytes.size <= IV_BYTES) return@withContext emptyMap()
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val body = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
        val plain = try {
            cipher.doFinal(body)
        } catch (e: Exception) {
            Log.e(TAG, e) { "Secret store unreadable; treating as empty" }
            return@withContext emptyMap()
        }
        json.decodeFromString(serializer, plain.decodeToString())
    }

    private suspend fun writeAll(values: Map<String, String>) = withContext(Dispatchers.IO) {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
        val body = cipher.doFinal(json.encodeToString(serializer, values).encodeToByteArray())
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(iv + body)
        if (!tmp.renameTo(file)) {
            file.writeBytes(iv + body)
            tmp.delete()
        }
    }

    private fun key(): SecretKey = keystoreKey() ?: softwareKey()

    private fun keystoreKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: run {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(false) // we supply our own random IV per write
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        }
    } catch (e: Exception) {
        Log.w(TAG, e) { "Android Keystore unavailable; using software key" }
        null
    }

    private fun softwareKey(): SecretKey {
        usingSoftwareKey = true
        val bytes = if (fallbackKeyFile.exists()) {
            fallbackKeyFile.readBytes()
        } else {
            ByteArray(KEY_BITS / 8).also { SecureRandom().nextBytes(it); fallbackKeyFile.writeBytes(it) }
        }
        return SecretKeySpec(bytes, "AES")
    }

    companion object {
        private const val TAG = "Secrets"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "clickarr-secrets"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** Key under which a provider connection's credential blob is stored. */
        fun providerKey(providerId: String) = "provider:$providerId"

        /** Household device token for this install (Phase 2). */
        const val HOUSEHOLD_TOKEN = "household:token"
    }
}
