package com.progboy.hermeslink

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class M4UiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun find(v: View, tag: String): View? {
        if (v.contentDescription == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }
    private fun awaitReady(activity: MainActivity) {
        val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = find(activity.window.decorView, "lock_now")?.isEnabled == true }
            if (ready) return
            Thread.sleep(25)
        }
        fail("Manager did not become ready")
    }
    @Test fun lockReturnAndStaleConfirmationCannotSaveDiscardedPassword() {
        val context = instrumentation.targetContext
        VaultSession.unlock()
        assertFalse("Regression requires the default-off gate", SettingsStore.gateEnabled(context))
        val h = HostProfile(id = "m4-dialog-stale", sshPassword = "saved-password", sshPin = "SHA256:" + "A".repeat(43), hostKeyType = "ssh-rsa", pinVerified = true)
        SettingsStore.saveHost(context, h)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var positive: Button? = null
        try {
            awaitReady(activity)
            instrumentation.runOnMainSync {
                find(activity.window.decorView, "hosts_tab")!!.performClick()
                find(activity.window.decorView, "edit_host_${h.id}")!!.performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                (find(activity.window.decorView, "ssh_password") as EditText).setText("discarded-password")
                (find(activity.window.decorView, "ssh_pin") as EditText).setText("SHA256:" + "B".repeat(43))
                (find(activity.window.decorView, "pin_verified") as CheckBox).isChecked = true
                find(activity.window.decorView, "save_host")!!.performClick()
                positive = android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .mapNotNull { it.findViewById<Button>(android.R.id.button1) }.single { it.text.toString() == "Replace verified key" }
                VaultSession.lock()
                // Deterministically leave and return with the default-off gate; no PIN UI is needed.
                instrumentation.callActivityOnStop(activity)
                instrumentation.callActivityOnStart(activity)
            }
            awaitReady(activity)
            instrumentation.runOnMainSync {
                assertFalse("Lock must dismiss pending confirmation before any click", positive!!.isShown)
                // A queued click can outlive dismissal: deliberately invoke the retained button.
                positive!!.performClick()
            }
            instrumentation.waitForIdleSync()
            val task = MainActivity::class.java.getDeclaredField("activeTask").apply { isAccessible = true }
            val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < end) {
                var active = false
                instrumentation.runOnMainSync { active = (task.get(activity) as? kotlinx.coroutines.Job)?.isActive == true }
                if (!active) break
                Thread.sleep(25)
            }
            val saved = SettingsStore.loadState(context).hosts.single { it.id == h.id }
            assertEquals("Lock must discard the captured save payload", "saved-password", saved.sshPassword)
            assertEquals(h.sshPin, saved.sshPin)
            instrumentation.runOnMainSync { assertFalse("Lock must dismiss pending confirmation", positive!!.isShown) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            VaultSession.unlock(); SettingsStore.deleteHost(context, h.id)
        }
    }
    @Test fun lockingClearsSecretEditorAndAlwaysOffersAnUnlockSurface() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val end = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < end) {
                var ready = false
                instrumentation.runOnMainSync { ready = find(activity.window.decorView, "hosts_tab")?.isEnabled == true }
                if (ready) break
                Thread.sleep(100)
            }
            instrumentation.runOnMainSync {
                find(activity.window.decorView, "hosts_tab")!!.performClick()
                find(activity.window.decorView, "add_host")!!.performClick()
                (find(activity.window.decorView, "ssh_password") as EditText).setText("session-editor-secret")
                VaultSession.lock()
                assertNull(find(activity.window.decorView, "ssh_password"))
                assertNotNull("Cancellation/lock must not strand a blank editor", find(activity.window.decorView, "unlock_vault"))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun hostEditorExposesExplicitKeyVerificationRememberAndPickerActions() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val end = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < end) {
                var ready = false
                instrumentation.runOnMainSync { ready = find(activity.window.decorView, "hosts_tab")?.isEnabled == true }
                if (ready) break
                Thread.sleep(100)
            }
            instrumentation.runOnMainSync {
                find(activity.window.decorView, "hosts_tab")!!.performClick()
                find(activity.window.decorView, "add_host")!!.performClick()
                for (tag in listOf("auth_method", "host_key_type", "pin_verified", "import_key", "remember_password", "remember_key", "remember_passphrase")) {
                    assertNotNull("Missing M4 host control: $tag", find(activity.window.decorView, tag))
                }
                assertFalse((find(activity.window.decorView, "pin_verified") as CheckBox).isChecked)
                assertFalse((find(activity.window.decorView, "remember_passphrase") as CheckBox).isChecked)
                val confirmed = find(activity.window.decorView, "pin_verified") as CheckBox
                confirmed.isChecked = true
                (find(activity.window.decorView, "ssh_pin") as EditText).setText("SHA256:" + "A".repeat(43))
                assertFalse("Changing a fingerprint must invalidate prior confirmation", confirmed.isChecked)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
