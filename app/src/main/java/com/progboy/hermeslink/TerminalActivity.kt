package com.progboy.hermeslink

import android.os.Bundle
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.termux.terminal.RemoteSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.*

/** M5 intentionally disconnects and clears on stop/rotation. No implicit remote reattach. */
class TerminalActivity:SecureActivity() {
    private lateinit var terminal:TerminalView
    private lateinit var model:RemoteTerminalSession
    private lateinit var controller:TerminalController
    private lateinit var outputView:TextView
    private lateinit var stderrView:TextView
    private var stderr=BoundedOutput(2048)
    private var stderrDecoder=StreamTextDecoder()
    private var control=false
    private lateinit var controlButton:Button
    private var pasteDialog:androidx.appcompat.app.AlertDialog?=null
    private var launchDialog:androidx.appcompat.app.AlertDialog?=null
    private var ready=false
    private var accessEpoch=VaultSession.epoch()
    private fun permitted()=ready && accessEpoch==VaultSession.epoch()
    private var generation=0L
    private var loading:Job?=null

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        setContentView(root); applySafeInsets(window,root)
        root.addView(TextView(this).apply { text="Interactive PTY • leaving or rotating closes and clears this terminal. Remote work may continue; no automatic retry." })
        val actions=LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply { addView(actions) })
        fun action(label:String, run:()->Unit) { actions.addView(Button(this).apply { text=label; setOnClickListener { run() } }) }
        action("Host shell") { connect(false) }
        action("Container shell") { connect(true) }
        action("Launch agent") { configureLaunch() }
        action("Paste") { pasteClipboard() }
        action("Disconnect") { disconnect(); outputView.text="Disconnected; remote outcome unknown." }
        outputView=TextView(this).apply { text="No connection started. Choose an explicit action."; isSaveEnabled=false }
        root.addView(outputView)
        stderrView=TextView(this).apply { isSaveEnabled=false; maxLines=3 }
        root.addView(stderrView)
        controller=TerminalController(onOutput={ if(permitted()) { model.append(it); terminal.onScreenUpdated() } },
            onState={ if(permitted()) outputView.text="[$it]" },
            onErrorOutput={ if(permitted()) stderrView.text=stderr.append(stderrDecoder.append(it,it.size)) })
        terminal=SafeTerminalView(this,::permitted,::requestPaste,::pasteClipboard).apply {
            isSaveEnabled=false; importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextSize((14*resources.displayMetrics.scaledDensity).toInt())
            setTerminalViewClient(client())
            contentDescription="Interactive remote terminal"
        }
        root.addView(terminal,LinearLayout.LayoutParams(-1,0,1f))
        val keys=LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply { addView(keys) })
        fun key(label:String, description:String=label, action:()->Unit):Button = Button(this).apply {
            text=label; contentDescription=description; minWidth=48; minimumWidth=48
            setOnClickListener { if(permitted()) action(); terminal.requestFocus() }; keys.addView(this)
        }
        controlButton=key("Ctrl","Control key latch") { control=!control; updateControl() }
        key("Esc","Escape") { model.write("\u001b") }
        key("Tab") { model.write("\t") }
        for((label,code) in listOf("↑" to KeyEvent.KEYCODE_DPAD_UP,"↓" to KeyEvent.KEYCODE_DPAD_DOWN,"←" to KeyEvent.KEYCODE_DPAD_LEFT,"→" to KeyEvent.KEYCODE_DPAD_RIGHT)) {
            key(label,"Arrow $label") { terminal.onKeyDown(code,KeyEvent(KeyEvent.ACTION_DOWN,code)) }
        }
        key("Ctrl-C","Interrupt (Control C)") { model.write("\u0003") }
        resetModel()
    }
    private fun updateControl() { controlButton.isSelected=control; controlButton.contentDescription=if(control) "Control key on; next key" else "Control key off" }

    private fun resetModel() {
        (terminal as? SafeTerminalView)?.invalidateInput()
        if(::model.isInitialized) model.dispose()
        model=RemoteTerminalSession({ if(permitted()) controller.send(it); it.fill(0) },controller::resize,{ text ->
            if(permitted()) (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Terminal selection",text))
        },::pasteClipboard)
        terminal.stopTextSelectionMode(); terminal.attachSession(model); terminal.onScreenUpdated()
        stderr.clear(); stderrView.text=""; stderrDecoder=StreamTextDecoder()
    }
    private fun client()=object:TerminalViewClient {
        override fun onScale(scale:Float)=1f
        override fun onSingleTapUp(e:MotionEvent?) { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(terminal,InputMethodManager.SHOW_IMPLICIT) }
        override fun shouldBackButtonBeMappedToEscape()=false
        override fun shouldEnforceCharBasedInput()=true
        override fun shouldUseCtrlSpaceWorkaround()=false
        override fun isTerminalViewSelected()=true
        override fun copyModeChanged(copyMode:Boolean) {}
        override fun onKeyDown(keyCode:Int,e:KeyEvent?,session:RemoteSession?)=false
        override fun onKeyUp(keyCode:Int,e:KeyEvent?)=false
        override fun onLongPress(event:MotionEvent?)=false
        override fun readControlKey():Boolean { val pressed=control; control=false; updateControl(); return pressed }
        override fun readAltKey()=false
        override fun readShiftKey()=false
        override fun readFnKey()=false
        override fun onCodePoint(codePoint:Int,ctrlDown:Boolean,session:RemoteSession?)=false
        override fun onEmulatorSet() {}
        override fun logError(tag:String?,message:String?) {}
        override fun logWarn(tag:String?,message:String?) {}
        override fun logInfo(tag:String?,message:String?) {}
        override fun logDebug(tag:String?,message:String?) {}
        override fun logVerbose(tag:String?,message:String?) {}
        override fun logStackTraceWithMessage(tag:String?,message:String?,e:Exception?) {}
        override fun logStackTrace(tag:String?,e:Exception?) {}
    }
    private fun connect(docker:Boolean) = connectExplicit { profile -> if(docker) TerminalPolicy.docker(profile.container) else null }
    private fun connectExplicit(commandFor:(AgentProfile)->String?) {
        if(!permitted()) return
        disconnect()
        val token=generation; val vaultEpoch=VaultSession.epoch()
        outputView.text="Connecting; checking saved host pin…"
        loading=lifecycleScope.launch {
            try {
                val profile=withContext(Dispatchers.IO) { SettingsStore.get(this@TerminalActivity,intent.getStringExtra("profile_id")) } ?: error("missing")
                if(token!=generation || !ready || vaultEpoch!=VaultSession.epoch()) return@launch
                val command=commandFor(profile)
                controller.connect(profile,command,model.size)
                terminal.requestFocus()
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(token==generation) outputView.text="Cannot connect. Check saved host/container configuration." }
        }
    }
    private fun configureLaunch() {
        if(!permitted()) return
        launchDialog?.dismiss()
        val epoch=VaultSession.epoch(); val token=generation
        val form=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL; setPadding(24,8,24,8)}
        form.addView(TextView(this).apply {text="Enter a command verified in this container. No provider CLI is assumed. Arguments are a JSON array of strings (not shell text). Locate uses only command -v and pwd, not the provider program. This configuration is not saved. Launch opens a new PTY and closes the current connection."})
        fun edit(label:String,initial:String="")=EditText(this).apply {
            hint=label; contentDescription=label; setText(initial); isSingleLine=true; isSaveEnabled=false
            importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters=arrayOf(android.text.InputFilter.LengthFilter(4096)); form.addView(this)
        }
        val command=edit("Exact installed command")
        val arguments=edit("Arguments JSON array","[]")
        val workdir=edit("Container working directory")
        val dialog=androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Launch agent once")
            .setView(ScrollView(this).apply {addView(form)})
            .setNegativeButton("Cancel",null).setNeutralButton("Locate (read only)",null).setPositiveButton("Launch once",null).create()
        launchDialog=dialog
        dialog.setOnDismissListener {
            command.setText(""); arguments.setText(""); workdir.setText("")
            if(launchDialog===dialog) launchDialog=null
        }
        dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            if(!permitted() || token!=generation || epoch!=VaultSession.epoch() || launchDialog!==dialog || !dialog.isShowing) return@setOnClickListener
            try {
                val executable=command.text.toString(); val directory=workdir.text.toString()
                TerminalPolicy.locate("validation",executable,directory)
                dialog.dismiss()
                connectExplicit { TerminalPolicy.locate(it.container,executable,directory) }
            } catch(_:Exception) {command.error="Enter an exact command and optional absolute work directory."}
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if(!permitted() || token!=generation || epoch!=VaultSession.epoch() || launchDialog!==dialog || !dialog.isShowing) return@setOnClickListener
            try {
                val executable=command.text.toString(); val directory=workdir.text.toString()
                val json=org.json.JSONArray(arguments.text.toString())
                require(json.length()<=64)
                val args=(0 until json.length()).map {i -> (json.get(i) as? String) ?: error("String arguments required")}
                // Validate all fields before dismissing; container is validated against the selected profile below.
                TerminalPolicy.docker("validation",executable,args,directory)
                dialog.dismiss()
                connectExplicit { TerminalPolicy.docker(it.container,executable,args,directory) }
            } catch(_:Exception) {command.error="Use an exact command, JSON string arguments, and an absolute work directory; no control characters."}
        }
    }
    private fun pasteClipboard() {
        if(!permitted()) return
        val clip=(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip ?: return
        // Text only: never dereference arbitrary clipboard URIs/Intents.
        clip.getItemAt(0).text?.let { requestPaste(it.take(4001).toString()) }
    }
    private fun requestPaste(text:String) {
        if(!permitted()) return
        if(text.length>4000 || text.toByteArray(Charsets.UTF_8).size>4000 || text.contains('\u0000')) {
            Toast.makeText(this,"Paste limited to 4000 UTF-8 bytes, without NUL.",Toast.LENGTH_LONG).show(); return
        }
        pasteDialog?.dismiss(); pasteDialog=null
        val token=generation; val epoch=VaultSession.epoch(); val session=model
        fun valid()=permitted() && generation==token && epoch==VaultSession.epoch() && model===session
        if(text.none { it=='\n'||it=='\r' }) { if(valid()) session.emulator.paste(text); return }
        val dialog=androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Paste multiple lines?")
            .setMessage("This text contains line breaks and may execute commands. Nothing has been sent. Paste only trusted text.")
            .setNegativeButton("Cancel",null).setPositiveButton("Paste",null).create()
        pasteDialog=dialog
        dialog.setOnDismissListener { if(pasteDialog===dialog) pasteDialog=null }
        dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if(pasteDialog===dialog && dialog.isShowing && valid()) session.emulator.paste(text)
            dialog.dismiss()
        }
    }
    private fun disconnect() {
        generation++; loading?.cancel(); loading=null
        pasteDialog?.dismiss(); pasteDialog=null
        launchDialog?.dismiss(); launchDialog=null
        control=false; if(::controlButton.isInitialized) updateControl()
        if(::controller.isInitialized) controller.disconnect()
        if(::terminal.isInitialized) resetModel()
        if(::outputView.isInitialized) outputView.text="Disconnected; choose an explicit action. Remote outcome unknown."
    }
    override fun onVaultReady() { accessEpoch=VaultSession.epoch(); ready=true }
    override fun onVaultLocked() { ready=false; disconnect(); if(::outputView.isInitialized) outputView.text="Locked; terminal closed and cleared." }
    override fun onStop() { ready=false; disconnect(); super.onStop() }
    override fun onDestroy() { if(::controller.isInitialized) controller.close(); super.onDestroy() }
}
