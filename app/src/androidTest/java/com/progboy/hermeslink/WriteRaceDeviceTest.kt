package com.progboy.hermeslink

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.*

/** M4 regression moved from deleted Activity-owned writer fields to the M5 I/O owner. */
class WriteRaceDeviceTest {
    @Test fun cancelledBlockedWriteCannotDisconnectReplacementConnection() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val oldFinished=CountDownLatch(1)
        val oldConnected=CountDownLatch(1); val newConnected=CountDownLatch(1); val newWritten=CountDownLatch(1)
        val states=CopyOnWriteArrayList<String>(); val replacement=ByteArrayOutputStream()
        val blocked=object:OutputStream() {
            override fun write(value:Int) {
                entered.countDown()
                try { check(release.await(10,TimeUnit.SECONDS)); throw IOException("Old disconnected socket") }
                finally {oldFinished.countDown()}
            }
        }
        var opened=0
        val controller=TerminalController(open={ _,_,_,op ->
            val first=++opened==1
            val peer=object:PtyPeer {
                override val input=ByteArrayInputStream(byteArrayOf())
                override val errors=ByteArrayInputStream(byteArrayOf())
                override val output=if(first) blocked else object:OutputStream() {
                    override fun write(value:Int) {replacement.write(value);if(replacement.size()=="new command\n".length)newWritten.countDown()}
                }
                @Volatile override var closed=false
                override val exitStatus=0
                override fun resize(size:TerminalSize) {}
            }
            op.own {peer.closed=true}; peer
        },onOutput={},onState={state -> states.add(state); if(state.startsWith("Connected")) {if(opened==1)oldConnected.countDown() else newConnected.countDown()} })
        try {
            ins.runOnMainSync {controller.connect(AgentProfile(),null,TerminalSize(80,24,8,20))}
            assertTrue(oldConnected.await(5,TimeUnit.SECONDS))
            ins.runOnMainSync {assertTrue(controller.send("old command\n".toByteArray()))}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            ins.runOnMainSync {controller.connect(AgentProfile(),null,TerminalSize(80,24,8,20))}
            assertTrue(newConnected.await(5,TimeUnit.SECONDS))
            release.countDown(); assertTrue(oldFinished.await(5,TimeUnit.SECONDS))
            ins.runOnMainSync {assertTrue(controller.send("new command\n".toByteArray()))}
            assertTrue(newWritten.await(5,TimeUnit.SECONDS))
            assertEquals("new command\n",replacement.toString("UTF-8"))
            assertFalse(states.any {it.startsWith("Failed")})
        } finally {release.countDown();ins.runOnMainSync {controller.close()}}
    }
}
