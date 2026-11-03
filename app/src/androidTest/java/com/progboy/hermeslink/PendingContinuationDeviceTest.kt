package com.progboy.hermeslink

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Holds only IO-to-main dispatch, after the real encrypted read has returned. */
class PendingContinuationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class PendingMain : CoroutineDispatcher() {
        val pending = LinkedBlockingQueue<Runnable>()
        override fun isDispatchNeeded(context: CoroutineContext) = Dispatchers.Main.immediate.isDispatchNeeded(context)
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun take(): Runnable = checkNotNull(pending.poll(15, TimeUnit.SECONDS)) { "No IO-to-main continuation" }
    }
    private fun installScope(activity: SecureActivity, dispatcher: PendingMain): Job {
        val job = SupervisorJob()
        val scope = Class.forName("androidx.lifecycle.LifecycleCoroutineScopeImpl")
            .getDeclaredConstructor(Lifecycle::class.java, CoroutineContext::class.java)
            .newInstance(activity.lifecycle, job + dispatcher) as LifecycleCoroutineScope
        val field = Lifecycle::class.java.declaredFields.single { AtomicReference::class.java.isAssignableFrom(it.type) }
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(activity.lifecycle) as AtomicReference<LifecycleCoroutineScope>).set(scope)
        return job
    }
    private fun checkAccess(activity: SecureActivity) {
        SecureActivity::class.java.getDeclaredMethod("checkAccess").apply { isAccessible = true }.invoke(activity)
    }
    private fun unlockView(view: View): View? {
        if (view.contentDescription == "unlock_vault") return view
        if (view is ViewGroup) for (i in 0 until view.childCount) unlockView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun available(activity: DiagnosticsActivity): Boolean = DiagnosticsActivity::class.java
        .getDeclaredField("available").apply { isAccessible = true }.getBoolean(activity)

    @Test fun dashboardReadCompletedBeforeLockCannotNavigateAndAuthenticatedRetryWorks() {
        dashboardRace(retryBeforeStale = false)
    }

    @Test fun staleDashboardFinallyCannotFinishReplacementGeneration() {
        dashboardRace(retryBeforeStale = true)
    }

    private fun dashboardRace(retryBeforeStale: Boolean) {
        val context = instrumentation.targetContext
        val hostId = "pending-dashboard-host"
        val agentId = "pending-dashboard-agent"
        // A prior activity's delayed background lock must not expire setup records
        // between their construction and the encrypted saves.
        instrumentation.runOnMainSync {
            VaultSession.unlock()
            SettingsStore.saveHost(context, HostProfile(id = hostId))
            SettingsStore.saveAgent(context, AgentRecord(id = agentId, hostId = hostId, webUrl = "https://example.com/dashboard"))
        }
        val dispatcher = PendingMain()
        var job: Job? = null
        val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if (activity is AgentActivity && stage == androidx.test.runner.lifecycle.Stage.CREATED) {
                job = installScope(activity, dispatcher)
            }
        }
        val lifecycleMonitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        instrumentation.runOnMainSync { lifecycleMonitor.addLifecycleCallback(callback) }
        val browser = instrumentation.addMonitor(android.content.IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE); addDataScheme("https")
        }, null, true)
        var activity: AgentActivity? = null
        try {
            val launched = instrumentation.startActivitySync(Intent(context, AgentActivity::class.java)
                .setAction(Intent.ACTION_MAIN).putExtra("profile_id", agentId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AgentActivity
            activity = launched
            val access = dispatcher.take()
            instrumentation.runOnMainSync { access.run() }
            val stale = dispatcher.take() // The actual encrypted profile lookup has completed.
            instrumentation.runOnMainSync {
                VaultSession.lock()
                assertTrue(unlockView(launched.window.decorView)!!.isShown)
            }
            fun beginRetry(): Runnable {
                instrumentation.runOnMainSync { VaultSession.unlock(); checkAccess(launched) }
                val retryAccess = dispatcher.take()
                instrumentation.runOnMainSync { retryAccess.run() }
                return dispatcher.take()
            }
            val replacement = if (retryBeforeStale) beginRetry() else null
            instrumentation.runOnMainSync {
                stale.run()
                assertEquals("Expired profile must not launch ACTION_VIEW", 0, browser.hits)
                assertFalse("Stale finally must not finish the activity or its replacement", launched.isFinishing)
                if (!retryBeforeStale) assertTrue(unlockView(launched.window.decorView)!!.isShown)
            }
            val retry = replacement ?: beginRetry()
            instrumentation.runOnMainSync {
                retry.run()
                assertEquals("Authenticated retry must open exactly one browser", 1, browser.hits)
                assertTrue("Current generation finishes after navigation", launched.isFinishing)
            }
        } finally {
            instrumentation.runOnMainSync {
                lifecycleMonitor.removeLifecycleCallback(callback)
                job?.cancel(); activity?.finish()
            }
            instrumentation.removeMonitor(browser)
            VaultSession.unlock()
            SettingsStore.delete(context, agentId)
            SettingsStore.deleteHost(context, hostId)
        }
    }

    @Test fun accessReadCompletedBeforeLockCannotHideCoverAndAuthenticatedRetryWorks() {
        VaultSession.unlock()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DiagnosticsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DiagnosticsActivity
        val dispatcher = PendingMain()
        var job: Job? = null
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync { ready = available(activity) }
                if (!ready) Thread.sleep(10)
            }
            assertTrue("Initial access must finish", ready)
            instrumentation.runOnMainSync { job = installScope(activity, dispatcher); checkAccess(activity) }
            val stale = dispatcher.take()
            instrumentation.runOnMainSync {
                VaultSession.lock()
                assertTrue(unlockView(activity.window.decorView)!!.isShown)
                stale.run()
                assertTrue("Stale allowed=true must not hide the unlock surface", unlockView(activity.window.decorView)!!.isShown)
                assertFalse("Stale check must not call onVaultReady", available(activity))
                VaultSession.unlock()
                checkAccess(activity)
            }
            val retry = dispatcher.take()
            instrumentation.runOnMainSync {
                retry.run()
                assertTrue("Authenticated retry must reach onVaultReady", available(activity))
                assertFalse(unlockView(activity.window.decorView)!!.isShown)
            }
        } finally {
            instrumentation.runOnMainSync { job?.cancel(); activity.finish() }
            VaultSession.unlock()
        }
    }
}
