/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.ui.twin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.twoyi.TwinSecretFormat
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the provider key on this device, encrypted with a key that never leaves
 * the Android keystore.
 *
 * The newest build promises "your key stays on this device", which rules out
 * storing it as plain text. AES/GCM with an authenticated, non-exportable keystore
 * key is the cheapest way to honour that without adding a dependency; the storage
 * envelope itself lives in `TwinSecretFormat`, which is host-tested.
 */
object TwinSecretStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "twin-provider-key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** Encrypted envelope for [plain], or null when the keystore is unavailable. */
    fun seal(context: Context, plain: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            TwinSecretFormat.encode(cipher.iv, ciphertext)
        } catch (failure: Exception) {
            null
        }
    }

    /** Plain key, or null when the envelope cannot be opened. */
    fun open(context: Context, envelope: String): String? {
        return try {
            val parts = TwinSecretFormat.decode(envelope)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, parts[0])
            )
            String(cipher.doFinal(parts[1]), Charsets.UTF_8)
        } catch (failure: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}