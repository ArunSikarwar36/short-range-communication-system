package ai.paai.link.security

import ai.paai.link.core.crypto.KeyPair
import ai.paai.link.core.crypto.Primitives
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-installation identity key (X25519). Android Keystore cannot hold X25519 keys on every
 * supported version, so the private key is wrapped with an AES-256-GCM key that lives inside the
 * Keystore (hardware-backed where the device supports it) and never leaves it. The wrapped blob is
 * stored in no-backup storage, so the identity is never copied to cloud backups or other phones.
 */
object KeystoreIdentity {
    private const val ALIAS = "paai_link_identity_wrap_v1"
    private const val FILE = "identity.v1.bin"
    private const val TAG = "KeystoreIdentity"

    /** Returns the key pair and whether it was newly created. */
    fun loadOrCreate(context: Context): Pair<KeyPair, Boolean> {
        val file = File(context.noBackupFilesDir, FILE)
        if (file.exists()) {
            try {
                return unwrap(file.readBytes()) to false
            } catch (e: Exception) {
                // Keystore key lost (e.g. device credential reset). Peers will need to pair again.
                Log.w(TAG, "stored identity could not be unlocked (${e.javaClass.simpleName}); creating a new one")
            }
        }
        val kp = Primitives.generateKeyPair()
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeBytes(wrap(kp))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not store identity" }
        }
        return kp to true
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(kp: KeyPair): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val priv = kp.privateKeyCopy()
        try {
            val ct = c.doFinal(priv)
            val iv = c.iv
            return byteArrayOf(1, iv.size.toByte()) + iv + ct
        } finally {
            priv.fill(0)
        }
    }

    private fun unwrap(blob: ByteArray): KeyPair {
        require(blob.size > 2 && blob[0] == 1.toByte()) { "unknown identity format" }
        val ivLen = blob[1].toInt()
        val iv = blob.copyOfRange(2, 2 + ivLen)
        val ct = blob.copyOfRange(2 + ivLen, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val priv = c.doFinal(ct)
        try {
            return KeyPair.fromPrivate(priv)
        } finally {
            priv.fill(0)
        }
    }
}
