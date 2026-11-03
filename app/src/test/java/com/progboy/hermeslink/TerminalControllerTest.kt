package com.progboy.hermeslink

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.*

class TerminalControllerTest {
    private class Peer: PtyPeer {
        val written = ByteArrayOutputStream()
        override val input = ByteArrayInputStream("new-session".toByteArray())
        override val errors = ByteArrayInputStream(byteArrayOf())
        override val output: OutputStream = written
        override val closed = true
        override val exitStatus = 7
        val sizes = mutableListOf<TerminalSize>()
        override fun resize(size:TerminalSize) { sizes.add(size) }
    }
    @Test fun missingExitStatusIsUnknownNotCompletion() {
        val ended=CountDownLatch(1);val states=CopyOnWriteArrayList<String>()
        val controller=TerminalController(Dispatchers.Default,{_,_,_,_->object:PtyPeer {
            override val input=ByteArrayInputStream(byteArrayOf());override val errors=ByteArrayInputStream(byteArrayOf());override val output=ByteArrayOutputStream()
            override val closed=true;override val exitStatus=-1;override fun resize(size:TerminalSize) {}
        }},{},{states.add(it);if(!it.startsWith("Connected"))ended.countDown()})
        try {controller.connect(AgentProfile(),null,TerminalSize(80,24,8,20));assertTrue(ended.await(2,TimeUnit.SECONDS));assertTrue(states.last().contains("outcome unknown"))}
        finally {controller.close()}
    }
    @Test fun acceptedInputSurvivesAnIdleReceiveTimeoutRace() {
        val queue=LinkedBlockingQueue<Runnable>()
        val io=object:CoroutineDispatcher() {
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) {queue.add(block)}
        }
        val written=ByteArrayOutputStream()
        val controller=TerminalController(Dispatchers.Unconfined,{_,_,_,_->object:PtyPeer {
            override val input=ByteArrayInputStream(byteArrayOf());override val errors=ByteArrayInputStream(byteArrayOf())
            override val output=written;override val closed=false;override val exitStatus=-1;override fun resize(size:TerminalSize) {}
        }},{},{},io=io)
        try {
            controller.connect(AgentProfile(),null,TerminalSize(80,24,8,20))
            queue.poll(1,TimeUnit.SECONDS)!!.run() // Open and schedule writer.
            queue.poll(1,TimeUnit.SECONDS)!!.run() // Writer waits for input.
            assertTrue(controller.send(byteArrayOf(42))) // Queue resumed continuation, but do not dispatch it yet.
            Thread.sleep(120) // Deliberately let the 30ms receive timeout win before dispatch.
            repeat(8) {queue.poll(50,TimeUnit.MILLISECONDS)?.run()}
            assertArrayEquals("Accepted input was discarded by a receive timeout",byteArrayOf(42),written.toByteArray())
        } finally {controller.close();repeat(8) {queue.poll(50,TimeUnit.MILLISECONDS)?.run()}}
    }
    @Test fun boundedByteQueueAcceptsAWholeImeCompositionBurst() {
        val connected=CountDownLatch(1);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val controller=TerminalController(Dispatchers.Default,{_,_,_,_->object:PtyPeer {
            override val input=ByteArrayInputStream(byteArrayOf());override val errors=ByteArrayInputStream(byteArrayOf())
            override val output=object:OutputStream() {override fun write(v:Int) {entered.countDown();release.await(5,TimeUnit.SECONDS)}}
            override val closed=false;override val exitStatus=-1;override fun resize(size:TerminalSize) {}
        }},{},{if(it.startsWith("Connected"))connected.countDown()})
        try {
            controller.connect(AgentProfile(),null,TerminalSize(80,24,8,20));assertTrue(connected.await(2,TimeUnit.SECONDS))
            assertTrue(controller.send(byteArrayOf(1)));assertTrue(entered.await(2,TimeUnit.SECONDS))
            repeat(4000) {assertTrue("A small IME composition exhausted message slots at $it bytes",controller.send(byteArrayOf(65)))}
        } finally {controller.close();release.countDown()}
    }
    @Test fun inputQueueIsBoundedAndNeverBlocksCallerOnSocket() {
        val connected=CountDownLatch(1); val entered=CountDownLatch(1);val release=CountDownLatch(1);val closed=CountDownLatch(1)
        val states=CopyOnWriteArrayList<String>()
        val controller=TerminalController(Dispatchers.Default,{ _,_,_,op ->
            op.own {closed.countDown()}
            object:PtyPeer {
                override val input=ByteArrayInputStream(byteArrayOf())
                override val errors=ByteArrayInputStream(byteArrayOf())
                override val output=object:OutputStream() {override fun write(v:Int) {entered.countDown();release.await(5,TimeUnit.SECONDS)}}
                override val closed=false
                override val exitStatus=-1
                override fun resize(size:TerminalSize) {}
            }
        },{}, {states.add(it); if(it.startsWith("Connected"))connected.countDown()})
        try {
            controller.connect(AgentProfile(),null,TerminalSize(80,24,8,20));assertTrue(connected.await(2,TimeUnit.SECONDS))
            assertTrue(controller.send(byteArrayOf(1)));assertTrue(entered.await(2,TimeUnit.SECONDS))
            val start=System.nanoTime()
            repeat(32) {assertTrue(controller.send(ByteArray(4096)))}
            assertFalse(controller.send(byteArrayOf(1)))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500)
            assertTrue(closed.await(2,TimeUnit.SECONDS))
            assertTrue(states.last().contains("queue full"))
            assertFalse(controller.send(byteArrayOf(1)))
        } finally {release.countDown();controller.close()}
    }
    @Test fun outputDeliveryAppliesBackpressureWithoutDroppingBytes() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val ended=CountDownLatch(1)
        val bytes=ByteArray(20000) { (it%251).toByte() };val received=ByteArrayOutputStream();val reads=java.util.concurrent.atomic.AtomicInteger()
        val input=object:ByteArrayInputStream(bytes) {override fun read(b:ByteArray,o:Int,n:Int):Int {reads.incrementAndGet();return super.read(b,o,n)}}
        val controller=TerminalController(Dispatchers.Default,{_,_,_,_ -> object:PtyPeer {
            override val input=input;override val errors=ByteArrayInputStream(byteArrayOf());override val output=ByteArrayOutputStream()
            override val closed=true;override val exitStatus=0;override fun resize(size:TerminalSize) {}
        }},{ assertTrue(it.size<=4096); if(reads.get()==1) {entered.countDown();release.await(5,TimeUnit.SECONDS)}; received.write(it) },{if(it.startsWith("Ended"))ended.countDown()})
        try {
            controller.connect(AgentProfile(),null,TerminalSize(80,24,8,20));assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertEquals(1,reads.get());release.countDown();assertTrue(ended.await(2,TimeUnit.SECONDS));assertArrayEquals(bytes,received.toByteArray())
        } finally {release.countDown();controller.close()}
    }
    @Test fun lateConnectCannotDeliverOrCloseReplacement() {
        val oldStarted=CountDownLatch(1); val releaseOld=CountDownLatch(1); val ended=CountDownLatch(1)
        val events=CopyOnWriteArrayList<String>(); val peer=Peer()
        val controller=TerminalController(Dispatchers.Default, { _, command, _, _ ->
            if(command=="old") { oldStarted.countDown(); releaseOld.await(5,TimeUnit.SECONDS); throw IOException("old failure") }
            peer
        }, { events.add(String(it)) }, { events.add(it); if(it.startsWith("Ended")) ended.countDown() })
        try {
            controller.connect(AgentProfile(),"old",TerminalSize(80,24,8,20))
            assertTrue(oldStarted.await(2,TimeUnit.SECONDS))
            controller.connect(AgentProfile(),"new",TerminalSize(80,24,8,20))
            assertTrue(ended.await(2,TimeUnit.SECONDS))
            releaseOld.countDown()
            runBlocking { controller.awaitIdle() }
            assertTrue(events.contains("new-session"))
            assertEquals(1,events.count { it.startsWith("Connected") })
            assertEquals(1,events.count { it.startsWith("Ended: 7") })
            assertFalse(events.any { it.startsWith("Failed") })
        } finally { releaseOld.countDown(); controller.close() }
    }
}
