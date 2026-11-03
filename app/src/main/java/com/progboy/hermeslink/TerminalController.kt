package com.progboy.hermeslink

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.io.OutputStream

/** Cell geometry is bounded; SSH wants terminal pixels, not cell pixels. */
data class TerminalSize(val columns:Int, val rows:Int, val cellWidth:Int, val cellHeight:Int) {
    init { require(columns in 2..512 && rows in 2..256 && cellWidth in 1..256 && cellHeight in 1..256) }
    val widthPixels get() = columns * cellWidth
    val heightPixels get() = rows * cellHeight
}
interface PtyPeer {
    val input:InputStream
    val errors:InputStream
    val output:OutputStream
    val closed:Boolean
    val exitStatus:Int
    fun resize(size:TerminalSize)
}

/** Blocking JSch calls are confined to the controller's IO workers, including window-change. */
object SshPty {
    fun open(profile:AgentProfile, command:String?, size:TerminalSize, operation:ConnectionOperation):PtyPeer {
        val ssh=ConnectionService().connect(profile,operation)
        operation.check()
        val ch:com.jcraft.jsch.Channel=if(command==null) (ssh.openChannel("shell") as ChannelShell).apply {
            setPty(true); setPtyType("xterm-256color",size.columns,size.rows,size.widthPixels,size.heightPixels)
        } else (ssh.openChannel("exec") as ChannelExec).apply {
            setCommand(command); setPty(true); setPtyType("xterm-256color",size.columns,size.rows,size.widthPixels,size.heightPixels)
        }
        operation.own { ch.disconnect() }
        val source=ch.inputStream; val errors=ch.extInputStream; val sink=ch.outputStream
        ch.connect(4000); operation.check()
        return object:PtyPeer {
            override val input=source
            override val errors=errors
            override val output=sink
            override val closed get()=ch.isClosed
            override val exitStatus get()=ch.exitStatus
            override fun resize(size:TerminalSize) {
                when(ch) {
                    is ChannelShell -> ch.setPtySize(size.columns,size.rows,size.widthPixels,size.heightPixels)
                    is ChannelExec -> ch.setPtySize(size.columns,size.rows,size.widthPixels,size.heightPixels)
                }
            }
        }
    }
}

/** No Activity or renderer ownership. Output backpressure waits for each <=4096 byte UI
 * batch; input has a 128 KiB byte budget and at most 4096 <=4096-byte chunks.
 * Overflow disconnects rather than corrupting a
 * command/escape sequence. Generation checks occur at delivery, not only at enqueue. */
class TerminalController(
    private val delivery:CoroutineDispatcher = Dispatchers.Main.immediate,
    private val open:(AgentProfile,String?,TerminalSize,ConnectionOperation)->PtyPeer = SshPty::open,
    private val onOutput:(ByteArray)->Unit,
    private val onState:(String)->Unit,
    private val onErrorOutput:(ByteArray)->Unit = onOutput,
    io:CoroutineDispatcher = Dispatchers.IO
) {
    private val lifetime=SupervisorJob()
    private val scope=CoroutineScope(lifetime+io)
    private var epoch=0L
    private class Run(val token:Long, val operation:ConnectionOperation, initial:TerminalSize) {
        val input=Channel<ByteArray>(4096, onUndeliveredElement={ it.fill(0) })
        var queuedBytes=0
        @Volatile var size=initial
        var job:Job?=null
        var connected=false
    }
    private var current:Run?=null
    @Synchronized fun connect(profile:AgentProfile, command:String?, size:TerminalSize) {
        disconnect()
        val run=Run(epoch,ConnectionOperation(),size); current=run
        run.job=scope.launch {
            try {
                val peer=open(profile,command,size,run.operation)
                ensureActive(); run.operation.check()
                deliver(run) { run.connected=true; onState("Connected: PTY") }
                coroutineScope {
                    val writer=launch {
                        var sent=size
                        while(isActive) {
                            val next=run.size
                            if(next!=sent) { peer.resize(next); sent=next }
                            // Never cancel a receive on an idle timer: prompt cancellation can
                            // discard an accepted keystroke before its resumed continuation runs.
                            val bytes=run.input.tryReceive().getOrNull()
                            if(bytes==null) { delay(15); continue }
                            synchronized(this@TerminalController) { run.queuedBytes-=bytes.size }
                            try { peer.output.write(bytes); peer.output.flush() } finally { bytes.fill(0) }
                        }
                    }
                    try {
                        val buffer=ByteArray(4096)
                        while(isActive) {
                            run.operation.check()
                            var read=false
                            for ((stream, callback) in listOf(peer.input to onOutput,peer.errors to onErrorOutput)) {
                                val available=stream.available()
                                if(available>0) {
                                    val n=stream.read(buffer,0,minOf(available,buffer.size))
                                    if(n>0) {
                                        read=true
                                        val batch=buffer.copyOf(n)
                                        try { deliver(run) { callback(batch) } } finally { batch.fill(0); buffer.fill(0) }
                                    }
                                }
                            }
                            if(!read && peer.closed) break
                            if(!read) delay(15)
                        }
                    } finally { writer.cancel() }
                }
                deliver(run) {
                    run.connected=false
                    onState(if(peer.exitStatus<0) "Disconnected: remote outcome unknown (no exit status)" else "Ended: ${peer.exitStatus} (remote channel closed)")
                }
            } catch(e:CancellationException) { throw e
            } catch(e:ConnectionFailure) {
                deliver(run) { run.connected=false; onState("Failed: ${e.stage}/${e.code}. ${e.code.action}") }
            } catch(_:Exception) {
                deliver(run) { run.connected=false; onState("Failed: terminal channel lost. Outcome unknown; no command was retried.") }
            } finally {
                run.input.cancel(); run.operation.cancel()
            }
        }
    }
    private suspend fun deliver(run:Run, action:()->Unit) = withContext(delivery) {
        synchronized(this@TerminalController) { if(current===run && epoch==run.token) action() }
    }
    @Synchronized fun send(bytes:ByteArray):Boolean {
        val run=current ?: return false
        if(!run.connected) return false
        if(bytes.isEmpty()) return true
        if(bytes.size<=4096 && run.queuedBytes+bytes.size<=128*1024) {
            val copy=bytes.copyOf()
            run.queuedBytes+=copy.size
            if(run.input.trySend(copy).isSuccess) return true
            run.queuedBytes-=copy.size; copy.fill(0)
        }
        disconnect(); onState("Failed: input queue full or paste too large; disconnected. Input was not retried.")
        return false
    }
    @Synchronized fun resize(size:TerminalSize) { current?.size=size }
    @Synchronized fun disconnect() {
        epoch++
        val old=current; current=null
        old?.input?.cancel(); old?.job?.cancel()
        if(old!=null) scope.launch { old.operation.cancel() }
    }
    suspend fun awaitIdle() { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
    fun close() { disconnect(); lifetime.complete() }
}
