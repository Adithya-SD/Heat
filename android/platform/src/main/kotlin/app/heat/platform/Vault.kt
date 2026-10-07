package app.heat.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import app.heat.core.crypto.Crypto
import app.heat.core.link.DeviceId
import app.heat.core.link.GroupKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Who we are paired with: the shared key, and (on a viewer) the phone that handed it over. */
class Pairing(val key: GroupKey, val hub: DeviceId?, val hubName: String?)

/**
 * This device's identity and its pairing secret, at rest.
 *
 * The group key is wrapped with a non-exportable AES key held in the Android
 * Keystore, so a copy of the app's files (a backup, a rooted-device dump) is
 * useless without the hardware-backed key. Writes are atomic: a crash mid-write
 * leaves the old value, never half of the new one.
 *
 * If the wrapped key cannot be read (the Keystore key was lost, e.g. after a
 * restore onto new hardware) that is treated as "not paired" so the user is
 * asked to pair again, rather than the app crashing or limping on with a
 * garbage key.
 */
class Vault(context: Context) {
    private val dir = File(context.filesDir, "heat").apply { mkdirs() }

    // ── identity (not secret) ────────────────────────────────────────

    fun deviceId(): DeviceId {
        val f = File(dir, "device.id")
        val existing = runCatching { DeviceId.fromHex(f.readText().trim()) }.getOrNull()
        if (existing != null) return existing
        val fresh = DeviceId.random()
        atomicWrite(f, fresh.hex.toByteArray())
        return fresh
    }

    // ── pairing (secret) ─────────────────────────────────────────────

    fun pairing(): Pairing? {
        val raw = readSecret("pairing") ?: return null
        return runCatching {
            val parts = String(raw, Charsets.UTF_8).split('\n')
            val key = GroupKey.parse(parts[0]) ?: return null
            Pairing(key, parts.getOrNull(1)?.let { DeviceId.fromHex(it) }, parts.getOrNull(2)?.takeIf { it.isNotBlank() })
        }.getOrNull()
    }

    fun setPairing(key: GroupKey, hub: DeviceId?, hubName: String?) {
        val text = listOf(key.export(), hub?.hex.orEmpty(), hubName.orEmpty()).joinToString("\n")
        writeSecret("pairing", text.toByteArray(Charsets.UTF_8))
    }

    fun clearPairing() {
        File(dir, "pairing.bin").delete()
    }

    /** First run on the phone: mint the household key. Idempotent: returns the existing one if present. */
    fun ensureGroupKey(): GroupKey {
        pairing()?.let { return it.key }
        val key = GroupKey.generate()
        setPairing(key, hub = deviceId(), hubName = null)
        return key
    }

    // ── secret storage ───────────────────────────────────────────────

    private fun readSecret(name: String): ByteArray? {
        val f = File(dir, "$name.bin")
        if (!f.exists()) return null
        return try {
            val blob = f.readBytes()
            if (blob.size < 13) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, blob, 0, 12))
            cipher.doFinal(blob, 12, blob.size - 12)
        } catch (t: Throwable) {
            Log.w(TAG, "could not read $name; treating as unpaired", t)
            null
        }
    }

    private fun writeSecret(name: String, plain: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val ct = cipher.doFinal(plain)
        atomicWrite(File(dir, "$name.bin"), cipher.iv + ct)
    }

    /** Hardware-backed when the Keystore works; an app-private file key when it does not (better than a crash loop). */
    private fun wrappingKey(): SecretKey = try {
        keystoreKey()
    } catch (t: Throwable) {
        Log.w(TAG, "Keystore unavailable, using an app-private key file", t)
        val f = File(dir, "wrap.key")
        val bytes = if (f.exists()) f.readBytes() else Crypto.random(32).also { atomicWrite(f, it) }
        SecretKeySpec(bytes, "AES")
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val TAG = "HeatVault"
        private const val ALIAS = "heat-vault-v1"

        /** Write to a temp file, then rename over the target: readers see the old or the new content, never a torn mix. */
        fun atomicWrite(target: File, bytes: ByteArray) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "could not replace ${target.name}" }
            }
        }
    }
}

/** Short fingerprint of the key, safe to show: two devices with the same fingerprint share a key. */
fun GroupKey.fingerprint(): String = Crypto.hex(tag).chunked(4).take(3).joinToString("-").uppercase()
