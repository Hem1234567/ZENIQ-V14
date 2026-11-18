package com.astra.agent.hub

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credential vault. Tokens and secrets are encrypted with an AES-GCM key that lives in the Android Keystore
 * (the key never leaves the secure hardware / keystore), and only the encrypted text is written to the app's storage.
 */
object Vault {
    private const val ALIAS = "zeniq_hub_vault_key"
    private const val PREFS = "zeniq_hub_vault"

    private fun key(): SecretKey {
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

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? = try {
        val (iv, ct) = blob.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Throwable) { null }

    /** All stored fields of one plugin (token, refresh token, client id ...) as a JSON object. */
    fun get(ctx: Context, plugin: String): JSONObject {
        val blob = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(plugin, null) ?: return JSONObject()
        val txt = decrypt(blob) ?: return JSONObject()
        return try { JSONObject(txt) } catch (_: Exception) { JSONObject() }
    }

    fun put(ctx: Context, plugin: String, data: JSONObject) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(plugin, encrypt(data.toString())).apply()
    }

    fun set(ctx: Context, plugin: String, field: String, value: String) {
        val o = get(ctx, plugin); o.put(field, value); put(ctx, plugin, o)
    }

    fun clear(ctx: Context, plugin: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(plugin).apply()
    }
}
