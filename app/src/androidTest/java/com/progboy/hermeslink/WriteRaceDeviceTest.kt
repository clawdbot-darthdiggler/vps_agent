package com.progboy.hermeslink

import android.content.Intent
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WriteRaceDeviceTest {
    @Test fun cancelledBlockedWriteCannotDisconnectReplacementConnection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, TerminalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TerminalActivity
        fun field(name: String) = TerminalActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun call(name: String) = TerminalActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacement = ByteArrayOutputStream()
        val blocked = object : OutputStream() {
            override fun write(value: Int) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Test did not release writer" }
                throw IOException("Old disconnected socket")
            }
        }
        try {
            lateinit var oldJob: Job
            instrumentation.runOnMainSync {
                field("writer").set(activity, blocked)
                (field("input").get(activity) as EditText).setText("old command")
                call("sendLine")
                oldJob = field("writeJob").get(activity) as Job
            }
            assertTrue("Write never blocked", entered.await(5, TimeUnit.SECONDS))
            var replacementGeneration = -1
            instrumentation.runOnMainSync {
                call("disconnect") // Cancels the old job while its blocking I/O is still running.
                replacementGeneration = field("generation").getInt(activity)
                field("writer").set(activity, replacement)
            }
            release.countDown()
            runBlocking { withTimeout(5000) { oldJob.join() } }
            instrumentation.runOnMainSync {
                assertEquals("Stale failure advanced new connection generation", replacementGeneration, field("generation").getInt(activity))
                assertSame("Stale failure cleared new writer", replacement, field("writer").get(activity))
                assertFalse((field("outputView").get(activity) as TextView).text.contains("Send failed"))
                (field("input").get(activity) as EditText).setText("new command")
                call("sendLine")
                oldJob = field("writeJob").get(activity) as Job
            }
            runBlocking { withTimeout(5000) { oldJob.join() } }
            assertEquals("new command\n", replacement.toString("UTF-8"))
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
