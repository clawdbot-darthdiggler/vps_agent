package com.progboy.hermeslink

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.autofill.AutofillValue
import android.view.inputmethod.*
import com.termux.view.TerminalView

/** Keep upstream renderer/input mapping. Gate bulk insertion and stale IME connections. */
class SafeTerminalView(context:Context, private val permitted:()->Boolean,
    private val pasteText:(String)->Unit, private val pasteClipboard:()->Unit):TerminalView(context,null) {
    private val inputs=mutableListOf<java.lang.ref.WeakReference<InputConnection>>()
    fun invalidateInput() { inputs.forEach {it.get()?.closeConnection()}; inputs.clear() }
    override fun autofill(value:AutofillValue) = Unit
    override fun getAutofillType()=AUTOFILL_TYPE_NONE
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
            if(event.action==MotionEvent.ACTION_DOWN && permitted()) pasteClipboard()
            return true
        }
        return super.onTouchEvent(event)
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(!permitted()) return true
        if(event.action==KeyEvent.ACTION_MULTIPLE && keyCode==KeyEvent.KEYCODE_UNKNOWN) {
            pasteText(event.characters.orEmpty()); return true
        }
        if(keyCode==KeyEvent.KEYCODE_V && event.isCtrlPressed && event.isShiftPressed) {
            pasteClipboard(); return true
        }
        return super.onKeyDown(keyCode,event)
    }
    override fun onCreateInputConnection(outAttrs:EditorInfo):InputConnection {
        invalidateInput()
        val base=super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions=outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        val session=currentSession
        return object:InputConnectionWrapper(base,false) {
            private var bulkComposition:String?=null
            private var closed=false
            private fun valid()=!closed && permitted() && session===currentSession
            override fun closeConnection() {
                closed=true; bulkComposition=null
                (base as? BaseInputConnection)?.editable?.clear()
                super.closeConnection()
            }
            override fun getTextBeforeCursor(n:Int,flags:Int):CharSequence=if(valid()) super.getTextBeforeCursor(n,flags) ?: "" else ""
            override fun getTextAfterCursor(n:Int,flags:Int):CharSequence=if(valid()) super.getTextAfterCursor(n,flags) ?: "" else ""
            override fun getSelectedText(flags:Int):CharSequence=if(valid()) super.getSelectedText(flags) ?: "" else ""
            override fun getExtractedText(request:ExtractedTextRequest,flags:Int):ExtractedText?=if(valid()) super.getExtractedText(request,flags) else ExtractedText().apply {text=""}
            override fun commitText(text:CharSequence,newCursorPosition:Int):Boolean {
                if(!valid()) return true
                bulkComposition=null
                if(text.length>4096 || text.any {it=='\r'||it=='\n'}) {
                    super.setComposingText("",1)
                    pasteText(text.take(4001).toString()); return true
                }
                return super.commitText(text,newCursorPosition)
            }
            override fun setComposingText(text:CharSequence,newCursorPosition:Int):Boolean {
                if(!valid()) return true
                if(text.length>4096 || text.any {it=='\r'||it=='\n'}) {
                    super.setComposingText("",1); bulkComposition=text.take(4001).toString(); return true
                }
                bulkComposition=null
                return super.setComposingText(text,newCursorPosition)
            }
            override fun finishComposingText():Boolean {
                if(!valid()) { bulkComposition=null; return true }
                val bulk=bulkComposition; bulkComposition=null
                if(bulk!=null) { pasteText(bulk); return true }
                return super.finishComposingText()
            }
            override fun sendKeyEvent(event:KeyEvent)=if(valid()) super.sendKeyEvent(event) else true
            override fun deleteSurroundingText(beforeLength:Int,afterLength:Int)=if(valid()) super.deleteSurroundingText(beforeLength,afterLength) else true
            override fun performContextMenuAction(id:Int):Boolean {
                if(id==android.R.id.paste || id==android.R.id.pasteAsPlainText) { if(valid()) pasteClipboard(); return true }
                return if(valid()) super.performContextMenuAction(id) else true
            }
        }.also {inputs.add(java.lang.ref.WeakReference(it))}
    }
}
