package ru.gigapisar.brain

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Service keys, one per service, encrypted with an AES key that lives in the Android
 * Keystore and never leaves it. What sits in the app's files is only ciphertext, like the
 * Keychain on the Mac. Several services can be kept at once and switched between.
 */
object KeyVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "giga_pisar_brain"
    private const val PREFS = "giga_pisar_brain_key"
    private const val PREFIX = "key_"

    fun save(
        context: Context,
        providerId: String,
        key: String,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        prefs(context).edit().putString(PREFIX + providerId, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun load(
        context: Context,
        providerId: String?,
    ): String? {
        if (providerId == null) return null
        val stored = prefs(context).getString(PREFIX + providerId, null) ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, 12))
            String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            // The Keystore key is gone (e.g. restored backup): the key has to be entered again.
            null
        }
    }

    /** Services that have a key saved, in the order of [BrainProviders.all]. */
    fun savedProviders(context: Context): List<BrainProvider> {
        val ids =
            prefs(context)
                .all.keys
                .filter { it.startsWith(PREFIX) }
                .map { it.removePrefix(PREFIX) }
                .toSet()
        return BrainProviders.all.filter { it.id in ids }
    }

    fun clear(
        context: Context,
        providerId: String,
    ) {
        prefs(context).edit().remove(PREFIX + providerId).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
