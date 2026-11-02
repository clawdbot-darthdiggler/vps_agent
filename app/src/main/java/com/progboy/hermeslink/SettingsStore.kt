package com.progboy.hermeslink

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

object SettingsStore {
    private fun prefs(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext, "hermeslink_secure",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    @Synchronized
    fun loadAll(context: Context): List<AgentProfile> {
        val prefs = prefs(context)
        val json = prefs.getString("profiles_v2", null)
        if (json == null) {
            val host = prefs.getString("host", null) ?: return emptyList()
            val imported = ProfilePolicy.migrateLegacy(host, prefs.getInt("port", 0),
                prefs.getString("password", "").orEmpty(), prefs.getString("mode", "web").orEmpty())
            // Atomic migration: archive ambiguous legacy secret encrypted, never use it for SSH.
            val edit = prefs.edit().putString("profiles_v2", encode(listOf(imported)))
                .putString("selected_v2", imported.id)
                .putString("legacy_unassigned_password", prefs.getString("password", ""))
                .remove("host").remove("port").remove("password").remove("mode")
            check(edit.commit()) { "Could not persist migrated settings" }
            return listOf(imported)
        }
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            AgentProfile(o.getString("id"), o.getString("name"), o.getString("provider"),
                o.optString("sshHost"), o.optInt("sshPort", 22), o.optString("sshUser"),
                o.optString("sshPassword"), o.optString("sshPin"), o.optString("container"),
                o.optString("webUrl"), o.optString("basicUser"), o.optString("basicPassword"))
        }
    }

    private fun encode(profiles: List<AgentProfile>): String = JSONArray().apply {
        profiles.forEach { p -> put(JSONObject().apply {
            put("id", p.id); put("name", p.name); put("provider", p.provider)
            put("sshHost", p.sshHost); put("sshPort", p.sshPort); put("sshUser", p.sshUser)
            put("sshPassword", p.sshPassword); put("sshPin", p.sshPin); put("container", p.container)
            put("webUrl", p.webUrl); put("basicUser", p.basicUser); put("basicPassword", p.basicPassword)
        }) }
    }.toString()

    @Synchronized
    fun selectedId(context: Context): String? = prefs(context).getString("selected_v2", null)
    @Synchronized
    fun select(context: Context, id: String) {
        check(prefs(context).edit().putString("selected_v2", id).commit()) { "Could not select profile" }
    }
    @Synchronized
    fun get(context: Context, id: String?): AgentProfile? = loadAll(context).find { it.id == id }
    @Synchronized
    fun save(context: Context, profile: AgentProfile) {
        val all = loadAll(context).filterNot { it.id == profile.id } + profile
        check(prefs(context).edit().putString("profiles_v2", encode(all))
            .putString("selected_v2", profile.id).commit()) { "Could not save profile" }
    }
    @Synchronized
    fun delete(context: Context, id: String) {
        val remaining = loadAll(context).filterNot { it.id == id }
        check(prefs(context).edit().putString("profiles_v2", encode(remaining))
            .putString("selected_v2", remaining.firstOrNull()?.id).commit()) { "Could not delete profile" }
    }
    @Synchronized
    fun forgetLegacySecret(context: Context) {
        check(prefs(context).edit().remove("legacy_unassigned_password").commit())
    }
}
