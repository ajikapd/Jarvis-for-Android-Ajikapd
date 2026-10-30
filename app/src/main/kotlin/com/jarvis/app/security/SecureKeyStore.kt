package com.jarvis.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.jarvis.core.security.KeyUtils
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureKeyStore(context: Context) {
    private val prefsName = "jarvis_keys"
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val keyAlias = "JarvisMasterKey"

    private fun getOrCreateSecretKey(): SecretKey? {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existingKey = keyStore.getKey(keyAlias, null) as? SecretKey
            if (existingKey != null) {
                return existingKey
            }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()

            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get or create secret key in AndroidKeyStore", e)
            return null
        }
    }

    fun setKey(providerId: String, key: String?) {
        val normId = KeyUtils.normalizeProviderId(providerId)
        if (normId.isBlank()) return

        if (key.isNullOrBlank() || KeyUtils.isPlaceholderKey(key)) {
            clearKey(normId)
            return
        }

        val cleaned = KeyUtils.cleanKey(key)
        val secretKey = getOrCreateSecretKey()
        if (secretKey == null) {
            Log.w(TAG, "SecretKey is null, cannot encrypt key for $normId")
            return
        }

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(cleaned.toByteArray(Charsets.UTF_8))

            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            val storedValue = "$ivB64:$cipherB64"

            prefs.edit().putString(normId, storedValue).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to encrypt key for $normId", e)
        }
    }

    fun getKey(providerId: String): String? {
        val normId = KeyUtils.normalizeProviderId(providerId)
        if (normId.isBlank()) return null

        val storedValue = prefs.getString(normId, null) ?: return null
        val parts = storedValue.split(":")
        if (parts.size != 2) {
            Log.w(TAG, "Invalid stored key format for $normId")
            return null
        }

        val secretKey = getOrCreateSecretKey() ?: return null

        try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(ciphertext)
            val decrypted = String(decryptedBytes, Charsets.UTF_8)
            Log.w(TAG, "decrypt ok for $normId")
            return decrypted
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt key for $normId. Key might be invalidated.", e)
            return null
        }
    }

    fun hasKey(providerId: String): Boolean {
        val normId = KeyUtils.normalizeProviderId(providerId)
        if (normId.isBlank()) return false
        return getKey(normId) != null
    }

    fun clearKey(providerId: String) {
        val normId = KeyUtils.normalizeProviderId(providerId)
        if (normId.isBlank()) return
        prefs.edit().remove(normId).apply()
    }

    fun providersWithKeys(): Set<String> {
        val result = mutableSetOf<String>()
        val allEntries = prefs.all
        for ((key, _) in allEntries) {
            if (hasKey(key)) {
                result.add(key)
            }
        }
        return result
    }

    companion object {
        private const val TAG = "Jarvis.SecureKeyStore"
    }
}
