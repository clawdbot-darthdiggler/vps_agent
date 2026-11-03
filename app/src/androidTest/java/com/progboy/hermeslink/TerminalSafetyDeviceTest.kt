package com.progboy.hermeslink

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.view.TerminalView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

class TerminalSafetyDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private fun field(a:TerminalActivity,n:String)=TerminalActivity::class.java.getDeclaredField(n).apply { isAccessible=true }.get(a)
    private fun call(a:TerminalActivity,n:String,arg:Boolean) { TerminalActivity::class.java.getDeclaredMethod(n,Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(a,arg) }
    private fun waitFor(check:()->Boolean) {
        val end=System.currentTimeMillis()+12000
        while(System.currentTimeMillis()<end) { var ok=false; ins.runOnMainSync {ok=check()}; if(ok)return; Thread.sleep(30) }
        fail("Terminal condition timed out")
    }
    private fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun withTerminal(action:(TerminalActivity,TerminalView)->Unit) {
        val name=InstrumentationRegistry.getArguments().getString("sshFixture"); assumeNotNull(name)
        val j=JSONObject(File(ins.targetContext.filesDir,name!!).readText())
        val h=HostProfile(id="m5-test-host",sshHost=j.getString("host"),sshPort=j.getInt("port"),sshUser=j.getString("username"),sshPassword=j.getString("password"),sshPin=j.getString("hostPin"),hostKeyType="ssh-rsa")
        SettingsStore.saveHost(ins.targetContext,h)
        val record=AgentRecord(id="m5-test-agent",hostId=h.id,container="test-container")
        SettingsStore.saveAgent(ins.targetContext,record)
        val a=ins.startActivitySync(Intent(ins.targetContext,TerminalActivity::class.java).putExtra("profile_id",record.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TerminalActivity
        try {
            waitFor {field(a,"ready")==true}
            lateinit var view:TerminalView
            ins.runOnMainSync {view=descendants(a.window.decorView).filterIsInstance<TerminalView>().single();call(a,"connect",false)}
            waitFor {view.mEmulator.screen.transcriptText.contains("READY")}
            try { action(a,view) } catch(e:AssertionError) {
                var evidence=""; ins.runOnMainSync { evidence="state="+(field(a,"outputView") as TextView).text+" transcript="+view.mEmulator.screen.transcriptText }
                throw AssertionError(evidence,e)
            }
        } finally {ins.runOnMainSync {a.finish()}; SettingsStore.delete(ins.targetContext,record.id);SettingsStore.deleteHost(ins.targetContext,h.id)}
    }
    @Test fun stderrDoesNotExposeUnguardedPlatformSelection() = withTerminal { a,_ ->
        assertFalse("Extended stderr bypasses guarded terminal copy",(field(a,"stderrView") as TextView).isTextSelectable)
    }
    @Test fun leavingTerminalClearsScreenAndConnectionStatus() = withTerminal { a,view ->
        val other=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor {view.mEmulator.screen.transcriptText.isBlank()}
            assertTrue("Stopped terminal still advertises a connection",(field(a,"outputView") as TextView).text.contains("Disconnected"))
        } finally {ins.runOnMainSync {other.finish()}}
    }
    @Test fun remoteDisconnectWithoutStatusDoesNotClaimCompletion() = withTerminal { a,view ->
        ins.runOnMainSync {view.currentSession.write("\u0005")}
        waitFor {(field(a,"outputView") as TextView).text.contains("outcome unknown",ignoreCase=true)}
    }
    @Test fun explicitClipboardAndBulkKeyboardPasteAreGatedAndAutofillIsDisabled() = withTerminal { a,view ->
        ins.runOnMainSync {
            val clip=ins.targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clip.setPrimaryClip(android.content.ClipData.newPlainText("test","a\rb"))
            descendants(a.window.decorView).filterIsInstance<Button>().single {it.text.toString()=="Paste"}.performClick()
            val dialog=field(a,"pasteDialog") as androidx.appcompat.app.AlertDialog
            assertTrue(dialog.isShowing);dialog.dismiss()
            val event=android.view.KeyEvent(android.os.SystemClock.uptimeMillis(),"a\nb",android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0)
            view.onKeyDown(android.view.KeyEvent.KEYCODE_UNKNOWN,event)
            val keyboardDialog=field(a,"pasteDialog") as androidx.appcompat.app.AlertDialog
            assertTrue(keyboardDialog.isShowing);keyboardDialog.dismiss()
            view.autofill(android.view.autofill.AutofillValue.forText("autofill\ncommand"))
            assertEquals(View.AUTOFILL_TYPE_NONE,view.autofillType)
            assertFalse(view.mEmulator.screen.transcriptText.contains("INPUT:"))
            clip.clearPrimaryClip()
        }
    }
    @Test fun queuedLockRevokesCopyBeforeUiListenerRuns() = withTerminal { _,view ->
        ins.runOnMainSync {
            val clipboard=ins.targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test","sentinel"))
            val worker=Thread {VaultSession.lock()};worker.start();worker.join(2000);assertFalse(worker.isAlive)
            view.currentSession.onCopyTextToClipboard("after-lock")
            assertEquals("Queued lock left selection authority alive","sentinel",clipboard.primaryClip!!.getItemAt(0).text.toString())
            clipboard.clearPrimaryClip()
        }
    }
    @Test fun rotationClosesAndClearsWithoutAutomaticReconnect() = withTerminal { a,view ->
        val monitor=ins.addMonitor(TerminalActivity::class.java.name,null,false)
        val old=view.currentSession
        var replacement:TerminalActivity?=null
        try {
            ins.runOnMainSync {a.requestedOrientation=if(a.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            replacement=monitor.waitForActivityWithTimeout(15000) as? TerminalActivity
            assertNotNull("Rotation did not recreate/close the nonpersistent terminal",replacement)
            ins.runOnMainSync {
                assertTrue(old.emulator.screen.transcriptText.isBlank())
                val fresh=descendants(replacement!!.window.decorView).filterIsInstance<TerminalView>().single()
                assertTrue(fresh.mEmulator.screen.transcriptText.isBlank())
                assertTrue((field(replacement!!,"outputView") as TextView).text.contains("No connection started"))
            }
        } finally {ins.removeMonitor(monitor);ins.runOnMainSync {replacement?.finish()}}
    }
    @Test fun lockClearsRetainedModelAndDismissesPendingPaste() = withTerminal { a,view ->
        ins.runOnMainSync {
            val old=view.currentSession
            val ime=view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            ime.commitText("secret\ncommand",1)
            val dialog=field(a,"pasteDialog") as androidx.appcompat.app.AlertDialog
            val positive=dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            ime.setComposingText("unsubmitted-secret",1)
            VaultSession.lock()
            assertFalse(dialog.isShowing)
            assertTrue("Old IME retained unsent input after lock",ime.getTextBeforeCursor(100,0).isNullOrEmpty())
            assertTrue("Retained old model still exposes terminal output",old.emulator.screen.transcriptText.isBlank())
            assertTrue(view.mEmulator.screen.transcriptText.isBlank())
            positive.performClick(); ime.commitText("stale\ninput",1)
            assertNull(field(a,"pasteDialog"))
            assertFalse(field(a,"ready") as Boolean)
            VaultSession.unlock()
        }
    }
    @Test fun staleSessionCannotSendIntoReplacement() = withTerminal { a,view ->
        lateinit var old:com.termux.terminal.RemoteSession
        ins.runOnMainSync {old=view.currentSession;call(a,"connect",false)}
        waitFor {view.mEmulator.screen.transcriptText.contains("READY")}
        ins.runOnMainSync {old.write("stale");view.currentSession.write("marker")}
        waitFor {view.mEmulator.screen.transcriptText.replace("\n","").contains("6d61726b6572")}
        ins.runOnMainSync {assertFalse("Old session wrote to replacement",view.mEmulator.screen.transcriptText.replace("\n","").contains("7374616c65"))}
    }
    @Test fun launchIsExplicitAndPreservesLiteralArguments() = withTerminal { a,view ->
        ins.runOnMainSync {
            val launch=descendants(a.window.decorView).filterIsInstance<Button>().find {it.text.toString()=="Launch agent"}
            assertNotNull("Missing explicit configured agent launch",launch)
            launch!!.performClick()
            val dialog=field(a,"launchDialog") as androidx.appcompat.app.AlertDialog
            assertEquals("Locate (read only)",dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).text.toString())
            val edits=descendants(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>()
            val command=edits.single {it.contentDescription=="Exact installed command"}
            assertEquals("",command.text.toString())
            command.setText("/opt/agent")
            edits.single {it.contentDescription=="Arguments JSON array"}.setText("[\"x'y\",\"$(touch /tmp/no)\",\"\"]")
            edits.single {it.contentDescription=="Container working directory"}.setText("/work dir")
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        waitFor {view.mEmulator.screen.transcriptText.contains("ARGV:")}
        ins.runOnMainSync {
            val text=view.mEmulator.screen.transcriptText.replace("\n","")
            assertTrue(text.contains("x'y")); assertTrue(text.contains("$(touch /tmp/no)")); assertTrue(text.contains("/work dir"))
        }
    }
    @Test fun imeMultilinePasteNeedsConfirmationIncludingCarriageReturn() = withTerminal { a,view ->
        lateinit var dialog:androidx.appcompat.app.AlertDialog
        ins.runOnMainSync {
            val ime=view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            ime.commitText("first\rsecond",1)
            val pending=runCatching {field(a,"pasteDialog")}.getOrNull()
            assertNotNull("IME multiline input bypassed paste confirmation",pending)
            dialog=pending as androidx.appcompat.app.AlertDialog
            assertTrue(dialog.isShowing)
            assertFalse(view.mEmulator.screen.transcriptText.contains("INPUT:"))
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        }
        waitFor {!dialog.isShowing}
        ins.runOnMainSync {
            assertFalse(view.mEmulator.screen.transcriptText.contains("INPUT:"))
            val bracketed="\u001b[?2004h".toByteArray();view.mEmulator.append(bracketed,bracketed.size)
            val ime=view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            ime.commitText("first\nsecond",1)
            dialog=field(a,"pasteDialog") as androidx.appcompat.app.AlertDialog
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        waitFor {
            val text=view.mEmulator.screen.transcriptText.replace("\n","")
            val hex=Regex("INPUT:([0-9a-f]+)").findAll(text).joinToString("") {it.groupValues[1]}
            hex=="1b5b3230307e66697273740d7365636f6e641b5b3230317e"
        }
    }
    @Test fun remoteClipboardDeniedButExplicitSelectionCopyWorks() = withTerminal { _,view ->
        ins.runOnMainSync {
            val clipboard=ins.targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test","sentinel"))
            val remote="\u001b]52;c;cmVtb3Rl\u0007".toByteArray()
            view.mEmulator.append(remote,remote.size)
            assertEquals("sentinel",clipboard.primaryClip!!.getItemAt(0).text.toString())
            val selected=view.mEmulator.screen.getSelectedText(0,0,10,0)
            view.currentSession.onCopyTextToClipboard(selected)
            assertEquals(selected,clipboard.primaryClip!!.getItemAt(0).text.toString())
            clipboard.clearPrimaryClip()
        }
    }
    @Test fun controlButtonsReachRealPtyAndResizeReachesPeer() = withTerminal { a,view ->
        ins.runOnMainSync {
            val buttons=descendants(a.window.decorView).filterIsInstance<Button>()
            for(label in listOf("Ctrl","Esc","Tab","↑","↓","←","→","Ctrl-C")) assertTrue("Missing $label",buttons.any {it.text.toString()==label})
            buttons.single {it.text.toString()=="Ctrl-C"}.performClick()
        }
        waitFor {view.mEmulator.screen.transcriptText.contains("INPUT:03")}
        for((label,hex) in listOf("Esc" to "1b","Tab" to "09","↑" to "1b5b41","↓" to "1b5b42","←" to "1b5b44","→" to "1b5b43")) {
            ins.runOnMainSync {descendants(a.window.decorView).filterIsInstance<Button>().single {it.text.toString()==label}.performClick()}
            waitFor {view.mEmulator.screen.transcriptText.contains("INPUT:$hex")}
        }
        ins.runOnMainSync {
            val mode="\u001b[?1h".toByteArray();view.mEmulator.append(mode,mode.size)
            descendants(a.window.decorView).filterIsInstance<Button>().single {it.text.toString()=="↑"}.performClick()
        }
        waitFor {view.mEmulator.screen.transcriptText.contains("INPUT:1b4f41")}
        ins.runOnMainSync {
            val ctrl=descendants(a.window.decorView).filterIsInstance<Button>().single {it.text.toString()=="Ctrl"}
            ctrl.performClick();view.inputCodePoint('a'.code,false,false);assertFalse(ctrl.isSelected)
            view.onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText("é中😀",1)
        }
        waitFor {val text=view.mEmulator.screen.transcriptText.replace("\n","");text.contains("INPUT:01") && text.contains("f09f9880")}
        waitFor {(field(a,"stderrView") as TextView).text.contains("EXTENDED-STDERR")}
        ins.runOnMainSync {view.currentSession.updateSize(90,30,8,20)}
        waitFor {view.mEmulator.screen.transcriptText.contains("SIZE:90x30:720x600")}
        ins.runOnMainSync {view.currentSession.write("\u0004")}
        try { waitFor {(field(a,"outputView") as TextView).text.contains("Ended: 7")} }
        catch(e:AssertionError) { var state=""; ins.runOnMainSync {state=(field(a,"outputView") as TextView).text.toString()}; throw AssertionError("Exit status: $state",e) }
    }
}
