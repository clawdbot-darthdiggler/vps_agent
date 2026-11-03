package com.progboy.hermeslink

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only: changes the disposable emulator's screen lock, then restores it.
 * Never run against a personal device. No app fixture key or password is printed. */
class DeviceGateSystemTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun view(v: View, tag: String): View? {
        if (v.contentDescription == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) view(v.getChildAt(i), tag)?.let { return it }
        return null
    }
    private fun waitFor(message: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
        fun structure(node: AccessibilityNodeInfo?): String {
            if (node == null) return "null"
            return "${node.packageName}/${node.className}/${node.viewIdResourceName} children=${node.childCount}\n" +
                (0 until node.childCount).joinToString("") { structure(node.getChild(it)) }
        }
        fail(message + "\n" + structure(ins.uiAutomation.rootInActiveWindow))
    }
    private fun editable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.packageName?.toString() in listOf("com.android.settings", "com.android.systemui") &&
            (node.isEditable || node.className?.toString() == "android.widget.EditText" || node.viewIdResourceName?.endsWith("password_entry") == true)) return true
        for (i in 0 until node.childCount) if (editable(node.getChild(i))) return true
        return false
    }
    @Test fun realSystemCredentialConfirmationEnablesGateAndCancellationKeepsItLocked() {
        assumeTrue("Disposable emulator system-credential test not requested", InstrumentationRegistry.getArguments().getString("deviceCredentialFixture") == "true")
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse("Refuse to change an existing secure device lock", keyguard.isDeviceSecure)
        assertFalse("Refuse to replace an existing app gate policy", SettingsStore.gateEnabled(context))
        var activity: MainActivity? = null
        try {
            assertTrue("Could not configure disposable emulator PIN", shell("locksettings set-pin 2468").contains("set to"))
            waitFor("Device did not become secure") { keyguard.isDeviceSecure }
            activity = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            fun click(tag: String) {
                waitFor("Missing enabled control: $tag") { var ready = false; ins.runOnMainSync { ready = view(activity!!.window.decorView, tag)?.isEnabled == true }; ready }
                ins.runOnMainSync { view(activity!!.window.decorView, tag)!!.performClick() }
            }
            click("vault_policy"); click("toggle_gate")
            waitFor("System credential confirmation did not appear") { editable(ins.uiAutomation.rootInActiveWindow) }
            shell("input text 2468"); shell("input keyevent 66")
            waitFor("Successful system confirmation did not enable gate") { SettingsStore.gateEnabled(context) }
            click("lock_now")
            assertFalse(VaultSession.unlocked)
            click("unlock_vault")
            waitFor("Second system credential confirmation did not appear") { editable(ins.uiAutomation.rootInActiveWindow) }
            shell("input keyevent 4") // Hide the credential keyboard first.
            shell("input keyevent 4") // Then cancel the system confirmation Activity.
            waitFor("App did not return to locked UI") { var ready = false; ins.runOnMainSync { ready = view(activity!!.window.decorView, "unlock_vault")?.isShown == true }; ready }
            assertTrue("Cancellation changed persisted gate policy", SettingsStore.gateEnabled(context))
            assertFalse("Cancellation unlocked the session", VaultSession.unlocked)
            try { SettingsStore.loadState(context); fail("Cancelled gate allowed secret access") } catch (_: IllegalStateException) { }
        } finally {
            ins.runOnMainSync { activity?.finish() }
            // Test-only cleanup; not an application recovery path.
            val p = androidx.security.crypto.EncryptedSharedPreferences.create(context, "hermeslink_secure",
                androidx.security.crypto.MasterKey.Builder(context).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            try { p.edit().putBoolean("device_gate", false).commit(); VaultSession.lock() }
            finally { shell("locksettings clear --old 2468") }
            assertFalse("Disposable device lock was not restored", keyguard.isDeviceSecure)
        }
    }
}
