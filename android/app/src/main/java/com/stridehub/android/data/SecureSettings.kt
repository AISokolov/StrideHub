package com.stridehub.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.stridehub.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BackendSettings(val baseUrl: String = "", val token: String = "") {
    val configured get() = baseUrl.isNotBlank() && token.isNotBlank()
}

fun validateBackendUrl(input: String, allowHttp: Boolean = BuildConfig.DEBUG): String {
    if (input.isBlank()) return ""
    val uri = try { URI(input.trim()) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid HTTPS URL.") }
    require((uri.scheme == "https" || (allowHttp && uri.scheme == "http")) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) {
        "Use a valid server origin without credentials, a query, or a fragment. HTTP is debug-only."
    }
    require(uri.path.isNullOrEmpty() || uri.path == "/") { "Enter only the server origin, for example https://stridehub.example.com." }
    return input.trim().trimEnd('/')
}

/** Android Keystore protects the AES key; only authenticated ciphertext reaches disk. */
interface BackendSettingsStore {
    suspend fun read(): BackendSettings
    suspend fun save(settings: BackendSettings)
}

class SecureSettings(context: Context) : BackendSettingsStore {
    private val preferences = context.getSharedPreferences("backend_secure", Context.MODE_PRIVATE)
    private val alias = "stridehub_backend_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override suspend fun read(): BackendSettings = withContext(Dispatchers.IO) {
        val encrypted = preferences.getString("config", null) ?: return@withContext (
            if (preferences.getBoolean("disabled", false)) BackendSettings()
            else BackendSettings(BuildConfig.SYNC_BASE_URL, BuildConfig.SYNC_TOKEN))
        try {
            val parts = encrypted.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            BackendSettings(json.getString("baseUrl"), json.getString("token"))
        } catch (_: Exception) {
            throw IllegalStateException("Saved credentials cannot be read. Save your backend settings again.")
        }
    }

    override suspend fun save(settings: BackendSettings) = withContext(Dispatchers.IO) {
        if (!settings.configured) {
            check(preferences.edit().remove("config").putBoolean("disabled", true).commit()) { "Could not save settings." }
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val plain = JSONObject().put("baseUrl", settings.baseUrl).put("token", settings.token).toString()
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(preferences.edit().putString("config", value).remove("disabled").commit()) { "Could not save settings." }
        }
    }
}
