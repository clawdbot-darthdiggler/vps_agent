package com.progboy.hermeslink

import com.termux.terminal.*

/** Model is touched only by the UI thread. Remote OSC clipboard callbacks are deliberately
 * separate from the RemoteSession selection callbacks, so OSC52 cannot impersonate a tap. */
class RemoteTerminalSession(
    private val send:(ByteArray)->Unit,
    private val resized:(TerminalSize)->Unit,
    private val copy:(String)->Unit,
    private val paste:()->Unit
):RemoteSession {
    private var active=true
    var size=TerminalSize(80,24,8,20); private set
    private val output=object:TerminalOutput() {
        override fun write(data:ByteArray,offset:Int,count:Int) { if(active) send(data.copyOfRange(offset,offset+count)) }
        override fun titleChanged(oldTitle:String?,newTitle:String?) = Unit
        override fun onCopyTextToClipboard(text:String?) = Unit // Deny remote OSC52 writes.
        override fun onPasteTextFromClipboard() = Unit // Deny remote clipboard queries.
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
    private val model=TerminalEmulator(output,80,24,8,20,2000,null)
    override fun getEmulator()=model
    override fun updateSize(columns:Int,rows:Int,cellWidth:Int,cellHeight:Int) {
        if(!active) return
        size=TerminalSize(columns.coerceIn(2,512),rows.coerceIn(2,256),cellWidth.coerceIn(1,256),cellHeight.coerceIn(1,256))
        model.resize(size.columns,size.rows,size.cellWidth,size.cellHeight)
        resized(size)
    }
    override fun write(data:String) { if(active) send(data.toByteArray(Charsets.UTF_8)) }
    override fun writeCodePoint(alt:Boolean,codePoint:Int) { write((if(alt) "\u001b" else "")+String(Character.toChars(codePoint))) }
    override fun onCopyTextToClipboard(text:String) { if(active) copy(text) }
    override fun onPasteTextFromClipboard() { if(active) paste() }
    fun append(bytes:ByteArray) { if(active) model.append(bytes,bytes.size) }
    fun dispose() {
        active=false
        // Reset parser first, then clear both buffers/history/title, including held model references.
        model.reset()
        val clear="\u001b[?1049h\u001b[2J\u001b[3J\u001b[?1049l\u001bc\u001b]0;\u0007".toByteArray()
        model.append(clear,clear.size)
    }
}
