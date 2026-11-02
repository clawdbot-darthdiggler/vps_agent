package com.progboy.hermeslink

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores connection credentials in EncryptedSharedPreferences.
 */
object SettingsStore {
    private const val PREFS = "hermeslink_secure"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_PASSWORD = "password"
    private const val KEY_MODE = "mode" // "web" | "terminal"

    private fun prefs(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun save(context: Context, host: String, port: Int, password: String, mode: String) {
        prefs(context).edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_PASSWORD, password)
            .putString(KEY_MODE, mode)
            .apply()
    }

    data class Config(val host: String, val port: Int, val password: String, val mode: String)

    fun load(context: Context): Config? {
        val p = prefs(context)
        val host = p.getString(KEY_HOST, null) ?: return null
        return Config(
            host = host,
            port = p.getInt(KEY_PORT, 0),
            password = p.getString(KEY_PASSWORD, "") ?: "",
            mode = p.getString(KEY_MODE, "web") ?: "web"
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
