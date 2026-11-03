package com.progboy.hermeslink

import android.content.Context
import android.content.ContextWrapper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CredentialLifecycleDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureName = "m4_credentials_test"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(fixtureName, mode)
    }
    private fun prefs() = EncryptedSharedPreferences.create(context, "hermeslink_secure",
        MasterKey.Builder(base).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    @Test fun compatibilitySavePreservesKeyAuthAndRememberPolicy() {
        val p = prefs(); p.edit().clear().commit()
        try {
            val h = HostProfile(id = "compat-key-host", rememberPassword = false, rememberKey = false)
            val a = AgentRecord(id = "compat-key-agent", hostId = h.id, rememberCredential = false)
            SettingsStore.saveHost(context, h); SettingsStore.saveAgent(context, a)
            val resolved = SettingsStore.get(context, a.id)!!.copy(authMethod = "private_key", privateKeyPem = "compat-key", keyPassphrase = "compat-passphrase", hostKeyType = "ssh-rsa")
            SettingsStore.save(context, resolved)
            val restored = SettingsStore.get(context, a.id)!!
            assertEquals("private_key", restored.authMethod)
            assertEquals("compat-key", restored.privateKeyPem)
            assertFalse(p.getString("profiles_v3", "")!!.contains("compat-key\""))
            assertFalse(SettingsStore.loadState(context).hosts.single().rememberKey)
        } finally { p.edit().clear().commit(); VaultSession.lock() }
    }
    @Test fun queuedSaveCannotRepopulateSessionSecretsAfterLock() {
        val p = prefs(); p.edit().clear().commit()
        val host = HostProfile(id = "m4-queued", sshPassword = "queued-secret", rememberPassword = false)
        var failure: Throwable? = null
        val worker = Thread { try { SettingsStore.saveHost(context, host) } catch (e: Throwable) { failure = e } }
        try {
            synchronized(SettingsStore) {
                worker.start()
                val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (worker.state != Thread.State.BLOCKED && System.nanoTime() < end) Thread.yield()
                assertEquals(Thread.State.BLOCKED, worker.state)
                VaultSession.lock()
            }
            worker.join(15000)
            assertFalse("Queued save did not finish", worker.isAlive)
            assertTrue("Stale submission must fail before persisting", failure is IllegalStateException)
            assertTrue(SettingsStore.loadState(context).hosts.isEmpty())
        } finally { worker.join(15000); p.edit().clear().commit(); VaultSession.lock() }
    }
    @Test fun staleRememberedSubmissionsCannotMutateDiskEvenWithGateOff() {
        val p = prefs(); p.edit().clear().commit()
        try {
            SettingsStore.saveHost(context, HostProfile(id = "stale-host", sshPassword = "saved"))
            SettingsStore.saveAgent(context, AgentRecord(id = "stale-agent", hostId = "stale-host", basicPassword = "saved-agent"))
            val h = SettingsStore.loadState(context).hosts.single().copy(sshPassword = "discarded-host")
            val a = SettingsStore.loadState(context).agents.single().copy(basicPassword = "discarded-agent")
            val flat = SettingsStore.get(context, a.id)!!.copy(sshPassword = "discarded-compat", basicPassword = "discarded-compat-agent")
            val before = p.getString("profiles_v3", null)
            VaultSession.lock(); VaultSession.unlock()
            assertFalse(SettingsStore.gateEnabled(context))
            for (save in listOf<() -> Unit>({ SettingsStore.saveHost(context, h) },
                { SettingsStore.saveAgent(context, a) }, { SettingsStore.save(context, flat) })) {
                try { save(); fail("Stale remembered submission accepted") } catch (_: IllegalStateException) { }
                assertEquals(before, p.getString("profiles_v3", null))
            }
            SettingsStore.saveHost(context, SettingsStore.loadState(context).hosts.single().copy(name = "Fresh edit"))
            assertEquals("Fresh edit", SettingsStore.loadState(context).hosts.single().name)
        } finally { p.edit().clear().commit(); VaultSession.lock() }
    }
    @Test fun sessionOnlySecretCannotBeRestoredByASaveStartedBeforeLock() {
        val before = VaultSession.epoch()
        VaultSession.lock()
        VaultSession.remember(HostProfile(id = "stale", sshPassword = "stale-secret", rememberPassword = false), before)
        assertNull(VaultSession.host("stale"))
    }
    @Test fun lockedGateCannotReadOrResetEncryptedSettings() {
        val p = prefs(); p.edit().clear().commit()
        try {
            SettingsStore.saveHost(context, HostProfile(id = "gate-host", sshPassword = "retained"))
            val before = p.getString("profiles_v3", null)
            p.edit().putBoolean("device_gate", true).commit(); VaultSession.lock()
            try { SettingsStore.loadState(context); fail("Locked read succeeded") } catch (_: IllegalStateException) { }
            try { SettingsStore.setGateEnabled(context, false); fail("Locked disable succeeded") } catch (_: IllegalStateException) { }
            assertTrue(SettingsStore.gateEnabled(context)); assertEquals(before, p.getString("profiles_v3", null))
        } finally { p.edit().clear().commit(); VaultSession.lock() }
    }
    @Test fun forgetIsScopedAndEphemeralCredentialsAreNeverPersisted() {
        val p = prefs(); p.edit().clear().commit()
        try {
            val h = HostProfile(id = "m4-host", sshPassword = "session-secret", rememberPassword = false)
            SettingsStore.saveHost(context, h)
            SettingsStore.saveAgent(context, AgentRecord(id = "m4-agent", hostId = h.id, basicPassword = "agent-secret"))
            assertEquals("session-secret", SettingsStore.loadState(context).hosts.single().sshPassword)
            assertFalse(p.getString("profiles_v3", "")!!.contains("session-secret"))
            SettingsStore.forgetAgentCredentials(context, "m4-agent")
            assertEquals("session-secret", SettingsStore.loadState(context).hosts.single().sshPassword)
            assertEquals("", SettingsStore.get(context, "m4-agent")!!.basicPassword)
            VaultSession.lock()
            assertEquals("", SettingsStore.loadState(context).hosts.single().sshPassword)
            assertFalse(SettingsStore.gateEnabled(context))
        } finally { p.edit().clear().commit(); VaultSession.lock() }
    }
    @Test fun rememberedKeyAndOptionalPassphraseRoundTripEncrypted() {
        val p = prefs(); p.edit().clear().commit()
        try {
            val h = HostProfile(id = "m4-key-host", authMethod = "private_key", privateKeyPem = "synthetic-key", keyPassphrase = "session-passphrase", rememberPassphrase = false, hostKeyType = "ssh-rsa")
            SettingsStore.saveHost(context, h)
            assertEquals("session-passphrase", SettingsStore.loadState(context).hosts.single().keyPassphrase)
            VaultSession.lock()
            val restored = SettingsStore.loadState(context).hosts.single()
            assertEquals("synthetic-key", restored.privateKeyPem)
            assertEquals("", restored.keyPassphrase)
            assertEquals("private_key", restored.authMethod)
            SettingsStore.forgetHostCredentials(context, h.id)
            assertEquals("", SettingsStore.loadState(context).hosts.single().privateKeyPem)
            val onDisk = java.io.File(base.applicationInfo.dataDir, "shared_prefs/$fixtureName.xml").readText()
            assertFalse(onDisk.contains("synthetic-key")); assertFalse(onDisk.contains("session-passphrase"))
        } finally { p.edit().clear().commit(); VaultSession.lock() }
    }
}
