package com.progboy.hermeslink

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun systemBarInsetsKeepControlsOutOfSystemBars() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val insets = androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                        androidx.core.graphics.Insets.of(8, 100, 8, 180)).build()
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets)
                assertEquals(100, root.paddingTop)
                assertEquals(180, root.paddingBottom)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun profilesPersistSeparatelyAndSecretsAreNotPlaintext() {
        val first = AgentProfile(name = "Device test Hermes", sshPassword = "ssh-test-secret-ONLY",
            basicPassword = "web-test-secret-ONLY", webUrl = "https://hermes.example")
        val second = AgentProfile(name = "Device test OpenClaw", provider = "OpenClaw",
            sshPassword = "different-test-secret-ONLY", webUrl = "https://claw.example")
        try {
            SettingsStore.save(context, first)
            SettingsStore.save(context, second)
            assertEquals(first, SettingsStore.get(context, first.id))
            assertEquals(second, SettingsStore.get(context, second.id))
            assertEquals(second.id, SettingsStore.selectedId(context))
            SettingsStore.select(context, first.id)
            assertEquals(first.id, SettingsStore.selectedId(context))
            val onDisk = File(context.applicationInfo.dataDir, "shared_prefs/hermeslink_secure.xml").readText()
            for (secret in listOf(first.sshPassword, first.basicPassword, second.sshPassword)) {
                assertFalse("Secret leaked in preferences file", onDisk.contains(secret))
            }
            SettingsStore.delete(context, first.id)
            assertNull(SettingsStore.get(context, first.id))
            assertEquals(second, SettingsStore.get(context, second.id))
        } finally {
            SettingsStore.delete(context, first.id)
            SettingsStore.delete(context, second.id)
        }
    }

    @Test fun mainScreenLoadsPersistedProfileAfterActivityRestart() {
        val profile = AgentProfile(name = "Restart test", sshHost = "100.64.0.5", sshUser = "testuser",
            sshPassword = "not-a-real-password", provider = "OpenClaw")
        SettingsStore.save(context, profile)
        try {
            repeat(2) {
                val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    val deadline = System.currentTimeMillis() + 15000
                    var loaded = false
                    while (!loaded && System.currentTimeMillis() < deadline) {
                        instrumentation.runOnMainSync {
                            fun children(v: View): List<View> = listOf(v) +
                                if (v is ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
                            val values = children(activity.window.decorView).filterIsInstance<EditText>().map { it.text.toString() }
                            loaded = profile.name in values && profile.sshHost in values && profile.sshPassword in values
                        }
                        if (!loaded) Thread.sleep(100)
                    }
                    assertTrue("Saved profile did not load into UI", loaded)
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                    instrumentation.waitForIdleSync()
                }
            }
        } finally { SettingsStore.delete(context, profile.id) }
    }
}
