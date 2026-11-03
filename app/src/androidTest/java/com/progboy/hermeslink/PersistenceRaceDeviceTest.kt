package com.progboy.hermeslink

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PersistenceRaceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun storeOperationsShareReentrantMonitorAndConcurrentSavesSurvive() {
        val operations = setOf("loadAll", "loadState", "selectedId", "select", "get", "save", "saveHost", "saveAgent", "deleteHost", "delete", "forgetLegacySecret")
        for (method in SettingsStore::class.java.declaredMethods.filter { it.name in operations }) {
            assertTrue("${method.name} must hold the store-wide monitor", Modifier.isSynchronized(method.modifiers))
        }
        // Keep a real foreground lifetime: another test's delayed background lock
        // must not intentionally revoke the epochs of these concurrent-save fixtures.
        val foreground=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val profiles = (1..8).map { AgentProfile(name = "m3-concurrent-save-$it") }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val saves = profiles.map { p -> pool.submit { SettingsStore.save(context, p) } }
            saves.forEach { it.get(60, TimeUnit.SECONDS) }
            profiles.forEach { assertEquals(it, SettingsStore.get(context, it.id)) }
            val deletes = profiles.take(4).map { p -> pool.submit { SettingsStore.delete(context, p.id) } }
            deletes.forEach { it.get(60, TimeUnit.SECONDS) }
            profiles.take(4).forEach { assertNull(SettingsStore.get(context, it.id)) }
            profiles.drop(4).forEach { assertEquals(it, SettingsStore.get(context, it.id)) }
        } finally {
            pool.shutdownNow()
            try { profiles.forEach { SettingsStore.delete(context, it.id); SettingsStore.deleteHost(context, "legacy-host-${it.id}") } }
            finally { instrumentation.runOnMainSync {foreground.finish()} }
        }
    }

    @Test fun realSaveDisablesEditorsAndRestoresThemAfterSuccessOrStorageFailure() {
        val host = HostProfile(name = "m3-race-host")
        SettingsStore.saveHost(context, host)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun children(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
        fun view(tag: String) = children(activity.window.decorView).find { it.contentDescription == tag }
        fun await(tag: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                var ready = false
                instrumentation.runOnMainSync { ready = view(tag)?.isEnabled == true }
                if (ready) return
                Thread.sleep(30)
            }
            fail("UI did not enable $tag")
        }
        val prefs = EncryptedSharedPreferences.create(context, "hermeslink_secure",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        val pool = Executors.newSingleThreadExecutor()
        var backup: String? = null
        try {
            await("hosts_tab")
            instrumentation.runOnMainSync { view("hosts_tab")!!.performClick() }
            for (failSave in listOf(false, true)) {
                await("edit_host_${host.id}")
                instrumentation.runOnMainSync {
                    view("edit_host_${host.id}")!!.performClick()
                    (view("host_name") as EditText).setText(if (failSave) "m3-unsaved-after-failure" else "m3-saved-success")
                }
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                val held = pool.submit {
                    synchronized(SettingsStore) {
                        entered.countDown(); check(release.await(20, TimeUnit.SECONDS))
                    }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                try {
                    if (failSave) {
                        backup = prefs.getString("profiles_v3", null)!!
                        prefs.edit().putString("profiles_v3", "m3-intentional-corrupt-fixture").commit()
                    }
                    instrumentation.runOnMainSync {
                        view("save_host")!!.performClick()
                        val controls = children(activity.window.decorView).filter { it is EditText || it is Spinner || it is Button }
                        assertTrue(controls.isNotEmpty())
                        controls.forEach { assertFalse("Control editable during blocked real save", it.isEnabled) }
                    }
                } finally { release.countDown(); held.get(10, TimeUnit.SECONDS) }
                if (failSave) {
                    await("save_host")
                    instrumentation.runOnMainSync {
                        assertEquals("m3-unsaved-after-failure", (view("host_name") as EditText).text.toString())
                        assertTrue(view("host_name")!!.isEnabled)
                        assertTrue(view("hosts_tab")!!.isEnabled)
                    }
                    prefs.edit().putString("profiles_v3", backup).commit(); backup = null
                    assertEquals("m3-saved-success", SettingsStore.loadState(context).hosts.single { it.id == host.id }.name)
                } else await("edit_host_${host.id}")
            }
        } finally {
            backup?.let { prefs.edit().putString("profiles_v3", it).commit() }
            pool.shutdownNow()
            instrumentation.runOnMainSync { activity.finish() }
            SettingsStore.deleteHost(context, host.id)
        }
    }
}
