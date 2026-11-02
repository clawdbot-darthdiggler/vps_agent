package com.progboy.hermeslink

import android.content.Intent
import android.widget.EditText
import android.widget.Spinner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PersistenceRaceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun storeOperationsShareReentrantMonitorAndConcurrentSavesSurvive() {
        // Enforce the same object monitor for migration, reads and read-modify-write operations.
        val operations = setOf("loadAll", "selectedId", "select", "get", "save", "delete", "forgetLegacySecret")
        for (method in SettingsStore::class.java.declaredMethods.filter { it.name in operations }) {
            assertTrue("${method.name} must hold the store-wide monitor", Modifier.isSynchronized(method.modifiers))
        }
        val profiles = (1..8).map { AgentProfile(name = "Concurrent save $it") }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val saves = profiles.map { p -> pool.submit { SettingsStore.save(context, p) } }
            saves.forEach { it.get(20, TimeUnit.SECONDS) }
            profiles.forEach { assertEquals(it, SettingsStore.get(context, it.id)) }
            val deletes = profiles.take(4).map { p -> pool.submit { SettingsStore.delete(context, p.id) } }
            deletes.forEach { it.get(20, TimeUnit.SECONDS) }
            profiles.take(4).forEach { assertNull(SettingsStore.get(context, it.id)) }
            profiles.drop(4).forEach { assertEquals(it, SettingsStore.get(context, it.id)) }
        } finally {
            pool.shutdownNow()
            profiles.forEach { SettingsStore.delete(context, it.id) }
        }
    }

    @Test fun persistenceTaskDisablesEditorsAndRestoresThemAfterSuccessOrFailure() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun awaitIdle() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                var busy = true
                instrumentation.runOnMainSync { busy = field("busy").getBoolean(activity) }
                if (!busy) return
                Thread.sleep(20)
            }
            fail("Persistence task did not complete")
        }
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        try {
            awaitIdle()
            for (failTask in listOf(false, true)) {
                val gate = CompletableDeferred<Unit>().also { gates.add(it) }
                val block: suspend () -> Unit = { gate.await(); if (failTask) error("Expected test persistence failure") }
                instrumentation.runOnMainSync {
                    MainActivity::class.java.getDeclaredMethod("task", kotlin.jvm.functions.Function1::class.java)
                        .apply { isAccessible = true }.invoke(activity, block)
                    @Suppress("UNCHECKED_CAST")
                    val fields = field("fields").get(activity) as Map<String, EditText>
                    fields.forEach { (name, editor) -> assertFalse("$name editable during persistence", editor.isEnabled) }
                    assertFalse((field("provider").get(activity) as Spinner).isEnabled)
                    assertFalse((field("picker").get(activity) as Spinner).isEnabled)
                }
                gate.complete(Unit)
                awaitIdle()
                instrumentation.runOnMainSync {
                    @Suppress("UNCHECKED_CAST")
                    val fields = field("fields").get(activity) as Map<String, EditText>
                    fields.values.forEach { assertTrue(it.isEnabled) }
                    assertTrue((field("provider").get(activity) as Spinner).isEnabled)
                    assertTrue((field("picker").get(activity) as Spinner).isEnabled)
                }
            }
        } finally {
            gates.forEach { it.complete(Unit) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
