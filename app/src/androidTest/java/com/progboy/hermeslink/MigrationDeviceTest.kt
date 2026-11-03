package com.progboy.hermeslink

import android.content.Context
import android.content.ContextWrapper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MigrationDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureName = "m3_test_migration"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(fixtureName, mode)
    }
    private fun prefs() = EncryptedSharedPreferences.create(context, "hermeslink_secure",
        MasterKey.Builder(base).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    @Test fun sharedHostChangesResolveForBothAgentsAndDeletionCannotOrphan() {
        val p = prefs(); p.edit().clear().commit()
        fun operation(name: String, value: Any) {
            val method = SettingsStore::class.java.declaredMethods.find { it.name == name }
            assertNotNull("Missing shared-host operation $name", method)
            try { method!!.invoke(SettingsStore, context, value) }
            catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
        }
        val host = HostProfile(name = "Shared VPS", sshPassword = "machine-only")
        val a = AgentRecord(name = "Hermes", hostId = host.id, basicPassword = "web-one")
        val b = AgentRecord(name = "Claw", hostId = host.id, basicPassword = "web-two")
        try {
            operation("saveHost", host); operation("saveAgent", a); operation("saveAgent", b)
            operation("saveHost", host.copy(sshHost = "new.example", sshPassword = "rotated"))
            for (id in listOf(a.id, b.id)) {
                assertEquals("new.example", SettingsStore.get(context, id)!!.sshHost)
                assertEquals("rotated", SettingsStore.get(context, id)!!.sshPassword)
            }
            assertEquals("web-one", SettingsStore.get(context, a.id)!!.basicPassword)
            assertEquals("web-two", SettingsStore.get(context, b.id)!!.basicPassword)
            try { operation("deleteHost", host.id); fail("Referenced host deleted") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("Reassign")) }
            SettingsStore.delete(context, a.id)
            assertEquals(1, SettingsStore.loadState(context).hosts.size)
            assertNotNull(SettingsStore.get(context, b.id))
            SettingsStore.delete(context, b.id); operation("deleteHost", host.id)
            assertTrue(SettingsStore.loadState(context).hosts.isEmpty())
        } finally { p.edit().clear().commit() }
    }

    @Test fun malformedAndFutureSchemasFailClosedAndKeepRecoverySource() {
        val p = prefs(); p.edit().clear().commit()
        val source = "[{\"id\":\"m3-bad-type\",\"name\":\"Legacy\",\"provider\":\"Hermes\",\"sshPassword\":42}]"
        try {
            p.edit().putString("profiles_v2", source).commit()
            var rejected = false
            try { SettingsStore.loadState(context) } catch (_: Exception) { rejected = true }
            assertTrue("Wrong-type credentials must not be silently coerced", rejected)
            assertNull(p.getString("profiles_v3", null))
            assertEquals(source, p.getString("profiles_v2", null))
            for (invalid in listOf("{\"version\":4,\"hosts\":[],\"agents\":[]}", "{\"version\":3,\"hosts\":[],\"agents\":[{\"id\":\"x\"}]}")) {
                p.edit().putString("profiles_v3", invalid).commit()
                rejected = false
                try { SettingsStore.saveHost(context, HostProfile()) } catch (_: Exception) { rejected = true }
                assertTrue(rejected)
                assertEquals(invalid, p.getString("profiles_v3", null))
                assertEquals(source, p.getString("profiles_v2", null))
            }
        } finally { p.edit().clear().commit() }
    }

    @Test fun v2MigrationIsAtomicRecoverableAndDoesNotDeduplicateHosts() {
        val prefs = prefs(); prefs.edit().clear().commit()
        val source = JSONArray().apply { repeat(2) { i -> put(JSONObject().apply {
            put("id", "legacy-$i"); put("name", "Legacy $i"); put("provider", "Hermes")
            put("sshHost", "same.example"); put("sshPassword", "ssh-$i"); put("basicPassword", "web-$i")
        }) } }.toString()
        prefs.edit().putString("profiles_v2", source).putString("selected_v2", "legacy-1").commit()
        try {
            val resolved = SettingsStore.loadAll(context)
            assertEquals(2, resolved.size)
            assertEquals("ssh-1", resolved[1].sshPassword)
            val migrated = prefs.getString("profiles_v3", null)
            assertNotNull("Must persist the versioned shared-host schema", migrated)
            val json = JSONObject(migrated!!)
            assertEquals(3, json.getInt("version"))
            assertEquals(2, json.getJSONArray("hosts").length())
            assertEquals(source, prefs.getString("profiles_v2", null))
            assertEquals("legacy-1", SettingsStore.selectedId(context))
            prefs.edit().putString("profiles_v3", "broken").commit()
            try { SettingsStore.loadAll(context); fail("Corruption must not fall back to stale credentials") } catch (_: org.json.JSONException) { }
            assertEquals(source, prefs.getString("profiles_v2", null))
            // Explicit fixture recovery: remove damaged destination only after preserving evidence.
            prefs.edit().remove("profiles_v3").commit()
            assertEquals(resolved, SettingsStore.loadAll(context))
            val h = SettingsStore.loadState(context).hosts.first()
            SettingsStore.saveHost(context, h.copy(sshPassword = "rotated-password"))
            assertNull("First v3 write must atomically retire stale credentials", prefs.getString("profiles_v2", null))
            assertNull(prefs.getString("selected_v2", null))
            assertEquals("rotated-password", SettingsStore.get(context, "legacy-0")!!.sshPassword)
            val onDisk = java.io.File(base.applicationInfo.dataDir, "shared_prefs/$fixtureName.xml").readText()
            for (secret in listOf("rotated-password", "ssh-1", "web-0", "web-1")) assertFalse(onDisk.contains(secret))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun unversionedMigrationRetainsAmbiguousSecretWithoutAssigningItToSsh() {
        val p = prefs(); p.edit().clear().commit()
        try {
            p.edit().putString("host", "legacy.example").putInt("port", 8443)
                .putString("mode", "web").putString("password", "m3-ambiguous-secret").commit()
            val a = SettingsStore.loadAll(context).single()
            assertEquals("", a.sshPassword)
            assertEquals("m3-ambiguous-secret", a.basicPassword)
            assertEquals("https://legacy.example:8443", a.webUrl)
            assertEquals("m3-ambiguous-secret", p.getString("legacy_unassigned_password", null))
            assertFalse(p.contains("host")); assertFalse(p.contains("password"))
            assertEquals(a, SettingsStore.loadAll(context).single())
            SettingsStore.forgetLegacySecret(context)
            assertFalse(p.contains("legacy_unassigned_password"))
            assertEquals(a, SettingsStore.loadAll(context).single())
        } finally { p.edit().clear().commit() }
    }
}
