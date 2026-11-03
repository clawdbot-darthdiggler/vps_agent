package com.progboy.hermeslink

import org.junit.Assert.*
import org.junit.Test

class InteractiveTerminalDeviceTest {
    @Test fun remoteModelBoundsHistoryAndPreservesFragmentedUnicodeAndAnsi() {
        val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        ins.runOnMainSync {
            val sent=java.io.ByteArrayOutputStream()
            val session=RemoteTerminalSession({sent.write(it)},{},{},{})
            val bytes="é中😀\r\n\u001b[31mRED\u001b[0m\r\nABC\u001b[2DX".toByteArray()
            bytes.forEach {session.append(byteArrayOf(it))}
            val model=session.emulator
            assertTrue(model.screen.transcriptText.contains("é中😀"))
            assertEquals(1,com.termux.terminal.TextStyle.decodeForeColor(model.screen.getStyleAt(1,0)))
            assertTrue(model.screen.transcriptText.contains("AXC"))
            val before=model.screen.transcriptText
            session.write("not locally echoed")
            assertEquals(before,model.screen.transcriptText)
            assertEquals("not locally echoed",sent.toString("UTF-8"))
            repeat(2300) {session.append("\r\nrow-$it".toByteArray())}
            assertTrue(model.screen.activeRows<=2000)
            assertFalse(model.screen.transcriptText.contains("é中😀"))
            assertTrue(model.screen.transcriptText.contains("row-2299"))
            session.dispose();assertTrue(model.screen.transcriptText.isBlank())
        }
    }
    @Test fun terminalScreenUsesRendererWithoutAutomaticConnection() {
        val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(android.content.Intent(ins.targetContext,TerminalActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as TerminalActivity
        try { ins.runOnMainSync {
            fun hasRenderer(v:android.view.View):Boolean = v is com.termux.view.TerminalView || (v is android.view.ViewGroup && (0 until v.childCount).any { hasRenderer(v.getChildAt(it)) })
            assertTrue("Screen still uses line output instead of terminal",hasRenderer(activity.window.decorView))
        } } finally { ins.runOnMainSync {activity.finish()} }
    }
    @Test fun controlledSshPtyDrawsWithActualAndroidRenderer() {
        val ins = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val name = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("sshFixture")
        org.junit.Assume.assumeNotNull(name)
        val j = org.json.JSONObject(java.io.File(ins.targetContext.filesDir, name!!).readText())
        val p = AgentProfile(sshHost=j.getString("host"), sshPort=j.getInt("port"), sshUser=j.getString("username"), sshPassword=j.getString("password"), sshPin=j.getString("hostPin"), hostKeyType="ssh-rsa")
        val op = ConnectionOperation()
        try {
            val ssh = ConnectionService().connect(p, op)
            val ch = ssh.openChannel("shell") as com.jcraft.jsch.ChannelShell
            op.own { ch.disconnect() }
            ch.setPty(true); ch.setPtyType("xterm-256color",80,24,640,480)
            val source = ch.inputStream; val sink = ch.outputStream
            ch.connect(4000)
            val captured = java.io.ByteArrayOutputStream()
            val end = System.currentTimeMillis()+10000
            while (!captured.toString("UTF-8").contains("READY") && System.currentTimeMillis()<end) {
                if (source.available()>0) { val b=ByteArray(4096); val n=source.read(b); if(n>0) captured.write(b,0,n) } else Thread.sleep(10)
            }
            assertTrue(captured.toString("UTF-8").contains("PTY:80x24:640x480"))
            assertTrue(captured.toString("UTF-8").contains("KERNEL-PTY:raw"))
            ins.runOnMainSync {
                val output = object: com.termux.terminal.TerminalOutput() {
                    override fun write(b:ByteArray,o:Int,n:Int) { sink.write(b,o,n) }
                    override fun titleChanged(a:String?,b:String?) {}
                    override fun onCopyTextToClipboard(s:String?) {}
                    override fun onPasteTextFromClipboard() {}
                    override fun onBell() {}
                    override fun onColorsChanged() {}
                }
                val model = com.termux.terminal.TerminalEmulator(output,80,24,8,20,100, null)
                val session = object: com.termux.terminal.RemoteSession {
                    override fun getEmulator() = model
                    override fun updateSize(c:Int,r:Int,w:Int,h:Int) { model.resize(c,r,w,h) }
                    override fun write(s:String) { output.write(s) }
                    override fun writeCodePoint(alt:Boolean,cp:Int) { output.write(String(Character.toChars(cp))) }
                    override fun onCopyTextToClipboard(s:String) {}
                    override fun onPasteTextFromClipboard() {}
                }
                val view = com.termux.view.TerminalView(ins.targetContext,null)
                val client = java.lang.reflect.Proxy.newProxyInstance(com.termux.view.TerminalViewClient::class.java.classLoader, arrayOf(com.termux.view.TerminalViewClient::class.java)) { _,method,_ -> when(method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Float.TYPE -> 1f; else -> null } } as com.termux.view.TerminalViewClient
                view.setTerminalViewClient(client); view.setTextSize(16); view.attachSession(session)
                view.layout(0,0,800,480)
                val bytes=captured.toByteArray(); model.append(bytes,bytes.size); view.onScreenUpdated()
                assertTrue(model.screen.transcriptText.contains("é中😀"))
                assertTrue(model.screen.transcriptText.contains("AXC"))
                val bitmap=android.graphics.Bitmap.createBitmap(800,480,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                val pixels=IntArray(800*480); bitmap.getPixels(pixels,0,800,0,0,800,480)
                assertTrue("Renderer did not draw foreground cells", pixels.toSet().size>2)
                bitmap.recycle()
            }
        } finally { op.cancel() }
    }
    @Test fun realAndroidTerminalRendererIsIncluded() {
        val renderer = runCatching { Class.forName("com.termux.view.TerminalView") }.getOrNull()
        assertNotNull("M5 requires the real Android terminal view, not a line TextView", renderer)
    }
}
