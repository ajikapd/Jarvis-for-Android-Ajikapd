package com.jarvis.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.jarvis.core.llm.CloudKeyValidator
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureKeyStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setKey(providerId: String, key: String) {
        val id = CloudKeyValidator.normalizeProviderId(providerId)
        if (id.isBlank()) return
        val clean = CloudKeyValidator.sanitizeKey(key)
        if (CloudKeyValidator.isStubKey(clean)) {
            clearKey(id)
            return
        }
        runCatching {
            val secretKey = getOrCreateSecretKey() ?: return
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val envelope = CloudKeyValidator.encodeEnvelope(
                cipher.iv,
                cipher.doFinal(clean.toByteArray(Charsets.UTF_8)),
            )
            prefs.edit().putString(id, envelope).apply()
        }.onFailure { Log.w(TAG, "Unable to encrypt provider key") }
    }

    fun getKey(providerId: String): String? {
        val id = CloudKeyValidator.normalizeProviderId(providerId)
        if (id.isBlank()) return null
        val envelope = CloudKeyValidator.decodeEnvelope(prefs.getString(id, null)) ?: return null
        return runCatching {
            val (iv, ciphertext) = envelope
            val secretKey = getOrCreateSecretKey() ?: return@runCatching null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8).also {
                Log.d(TAG, "decrypt ok")
            }
        }.getOrElse {
            Log.w(TAG, "Unable to decrypt provider key")
            null
        }
    }

    fun hasKey(providerId: String): Boolean = getKey(providerId) != null

    fun clearKey(providerId: String) {
        val id = CloudKeyValidator.normalizeProviderId(providerId)
        if (id.isNotBlank()) prefs.edit().remove(id).apply()
    }

    fun providersWithKeys(): Set<String> = prefs.all.keys.filterTo(mutableSetOf()) { hasKey(it) }

    private fun getOrCreateSecretKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generator.generateKey()
        }
    }.onFailure { Log.w(TAG, "Unable to access AndroidKeyStore key") }.getOrNull()

    private companion object {
        const val TAG = "Jarvis.SecureKeyStore"
        const val PREFS_NAME = "jarvis_keys"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "JarvisMasterKey"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH = 128
    }
}
