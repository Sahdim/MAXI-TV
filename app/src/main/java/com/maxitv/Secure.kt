package com.maxitv

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Coffre pour les identifiants IPTV : chiffrés en AES-256-GCM avec une clé qui reste dans le coffre
 * matériel d'Android (Keystore) et ne peut pas en être extraite. Fire OS 6+ (Android 6+) : chiffré.
 * Sur un très vieux Fire OS 5, le coffre n'existe pas : les valeurs sont seulement encodées.
 */
object Secure {
    private const val ALIAS = "maxitv_secure_key"
    private const val PREFS = "maxitv_secure"
    private val keys = setOf("host", "user", "pass", "tmdb")

    private fun secretKey(): SecretKey? {
        if (Build.VERSION.SDK_INT < 23) return null
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val existing = ks.getKey(ALIAS, null)
            if (existing is SecretKey) return existing
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        } catch (e: Exception) {
            null
        }
    }

    fun get(ctx: Context, name: String): String {
        if (name !in keys) return ""
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return ""
        return try {
            if (stored.startsWith("g:")) {
                val key = secretKey() ?: return ""
                val data = Base64.decode(stored.substring(2), Base64.NO_WRAP)
                val iv = data.copyOfRange(0, 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
            } else if (stored.startsWith("p:")) {
                String(Base64.decode(stored.substring(2), Base64.NO_WRAP), Charsets.UTF_8)
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun set(ctx: Context, name: String, value: String) {
        if (name !in keys || value.length > 2000) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        try {
            val key = secretKey()
            val packed = if (key != null) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                "g:" + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
            } else {
                "p:" + Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }
            prefs.edit().putString(name, packed).apply()
        } catch (e: Exception) {
            // échec du chiffrement : on n'enregistre rien plutôt que d'écrire en clair
        }
    }
}
