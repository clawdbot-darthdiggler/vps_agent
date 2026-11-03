package com.progboy.hermeslink

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ManagerUiDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun children(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
    private fun view(a: Activity, tag: String) = children(a.window.decorView).find { it.contentDescription?.toString() == tag }
    private fun await(a: Activity, tag: String) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            ins.runOnMainSync { ready = view(a, tag)?.isEnabled == true }
            if (ready) return
            Thread.sleep(40)
        }
        fail("UI control unavailable: $tag")
    }
    @Test fun emptyListsGuideHostCreationBeforeAgentCreation() {
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(context, "hermeslink_secure",
            androidx.security.crypto.MasterKey.Builder(context).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        SettingsStore.loadState(context)
        val backup = prefs.getString("profiles_v3", null)!!
        prefs.edit().putString("profiles_v3", "{\"version\":3,\"selectedId\":null,\"hosts\":[],\"agents\":[]}").commit()
        val a = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await(a, "add_agent")
            ins.runOnMainSync {
                assertTrue(children(a.window.decorView).filterIsInstance<TextView>().any { it.text.contains("No agents yet") })
                view(a, "add_agent")!!.performClick()
                assertTrue((view(a, "status") as TextView).text.contains("Add a host"))
                view(a, "hosts_tab")!!.performClick()
                assertTrue(children(a.window.decorView).filterIsInstance<TextView>().any { it.text.contains("No hosts yet") })
                view(a, "add_host")!!.performClick()
                (view(a, "host_name") as EditText).setText("m3-empty-created-host")
                (view(a, "host_address") as EditText).setText("100.64.0.30")
                view(a, "save_host")!!.performClick()
            }
            await(a, "add_host")
            val saved = SettingsStore.loadState(context).hosts.single()
            assertEquals("m3-empty-created-host", saved.name)
            assertEquals("100.64.0.30", saved.sshHost)
            ins.runOnMainSync { assertNotNull(view(a, "host_card_${saved.id}")) }
        } finally { ins.runOnMainSync { a.finish() }; prefs.edit().putString("profiles_v3", backup).commit() }
    }

    @Test fun newAgentRequiresExplicitHostAndShellLaunchPassesOnlySavedId() {
        val h = HostProfile(name = "m3-create-host", sshHost = "100.64.0.20", sshUser = "tester", sshPin = "SHA256:" + "A".repeat(43))
        SettingsStore.saveHost(context, h)
        val a = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var savedId: String? = null
        val monitor = ins.addMonitor(TerminalActivity::class.java.name, null, false)
        var terminal: Activity? = null
        try {
            await(a, "add_agent")
            ins.runOnMainSync {
                view(a, "add_agent")!!.performClick()
                assertEquals(0, (view(a, "agent_host") as Spinner).selectedItemPosition)
                (view(a, "agent_name") as EditText).setText("m3-created-agent")
                view(a, "save_agent")!!.performClick()
                assertTrue((view(a, "status") as TextView).text.contains("Select a saved host"))
                val hostIndex = SettingsStore.loadState(context).hosts.indexOfFirst { it.id == h.id } + 1
                (view(a, "agent_host") as Spinner).setSelection(hostIndex)
                (view(a, "basic_password") as EditText).setText("m3-web-secret")
                view(a, "save_agent")!!.performClick()
            }
            await(a, "add_agent")
            val saved = SettingsStore.loadState(context).agents.single { it.name == "m3-created-agent" }
            savedId = saved.id
            assertEquals(h.id, saved.hostId)
            assertEquals("m3-web-secret", saved.basicPassword)
            ins.runOnMainSync { view(a, "open_shell_${saved.id}")!!.performClick() }
            terminal = ins.waitForMonitorWithTimeout(monitor, 15000)
            assertNotNull("Saved agent should open SSH controls", terminal)
            assertEquals(setOf("profile_id"), terminal!!.intent.extras!!.keySet())
            assertEquals(saved.id, terminal!!.intent.getStringExtra("profile_id"))
            assertEquals(saved.id, SettingsStore.selectedId(context))
        } finally {
            ins.removeMonitor(monitor)
            ins.runOnMainSync { terminal?.finish(); a.finish() }
            savedId?.let { SettingsStore.delete(context, it) }
            SettingsStore.deleteHost(context, h.id)
        }
    }

    @Test fun damagedStoreShowsRecoverableErrorWithoutEnablingDestructiveEditors() {
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(context, "hermeslink_secure",
            androidx.security.crypto.MasterKey.Builder(context).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        SettingsStore.loadState(context)
        val backup = prefs.getString("profiles_v3", null)!!
        prefs.edit().putString("profiles_v3", "m3-damaged-ui-fixture").commit()
        val a = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await(a, "retry_load")
            ins.runOnMainSync {
                assertFalse(view(a, "agents_tab")!!.isEnabled)
                assertNull(view(a, "add_agent"))
                assertTrue((view(a, "status") as TextView).text.contains("No credentials were reset"))
            }
            assertEquals("m3-damaged-ui-fixture", prefs.getString("profiles_v3", null))
            prefs.edit().putString("profiles_v3", backup).commit()
            ins.runOnMainSync { view(a, "retry_load")!!.performClick() }
            await(a, "add_agent")
        } finally { prefs.edit().putString("profiles_v3", backup).commit(); ins.runOnMainSync { a.finish() } }
    }

    @Test fun separateListsAndEditorsKeepHostAndAgentCredentialsApart() {
        val h = HostProfile(name = "m3-ui-host", sshHost = "100.64.0.10", sshPassword = "host-secret")
        val agent = AgentRecord(name = "m3-ui-agent", hostId = h.id, basicPassword = "web-secret")
        SettingsStore.saveHost(context, h); SettingsStore.saveAgent(context, agent)
        val a = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await(a, "agents_tab")
            ins.runOnMainSync {
                assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val card = view(a, "agent_card_${agent.id}") as ViewGroup
                val background = (card.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor
                val title = card.getChildAt(0) as TextView
                assertTrue("Card text must be readable", androidx.core.graphics.ColorUtils.calculateContrast(title.currentTextColor, background) >= 4.5)
                assertTrue(children(a.window.decorView).filterIsInstance<EditText>().isEmpty())
                view(a, "edit_agent_${agent.id}")!!.performClick()
            }
            await(a, "save_agent")
            ins.runOnMainSync {
                assertNull(view(a, "ssh_password"))
                assertEquals("web-secret", (view(a, "basic_password") as EditText).text.toString())
                assertEquals(SettingsStore.loadState(context).hosts.indexOfFirst { it.id == h.id } + 1, (view(a, "agent_host") as Spinner).selectedItemPosition)
                assertFalse((view(a, "basic_password") as EditText).isSaveEnabled)
                view(a, "hosts_tab")!!.performClick()
                assertNotNull(view(a, "host_card_${h.id}"))
                view(a, "edit_host_${h.id}")!!.performClick()
                assertNull(view(a, "basic_password"))
                assertEquals("host-secret", (view(a, "ssh_password") as EditText).text.toString())
                (view(a, "host_address") as EditText).setText("changed.example")
                view(a, "save_host")!!.performClick()
            }
            await(a, "edit_host_${h.id}")
            assertEquals("changed.example", SettingsStore.get(context, agent.id)!!.sshHost)
            ins.runOnMainSync { view(a, "edit_host_${h.id}")!!.performClick(); view(a, "delete_host")!!.performClick() }
            await(a, "save_host")
            ins.runOnMainSync { assertTrue((view(a, "status") as TextView).text.contains("Reassign")) }
        } finally {
            ins.runOnMainSync { a.finish() }
            SettingsStore.delete(context, agent.id); SettingsStore.deleteHost(context, h.id)
        }
    }
}
