package com.progboy.hermeslink

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SshFixtureDeviceTest {
    private fun fixture(): JSONObject {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Controlled SSH fixture not requested", args.containsKey("sshFixture"))
        val name = args.getString("sshFixture")!!
        require(name == File(name).name) { "Fixture must be a filesDir basename" }
        val f = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name)
        assertTrue("Requested SSH fixture file missing", f.isFile)
        return JSONObject(f.readText())
    }
    private fun profile(j: JSONObject) = AgentProfile(sshHost = j.getString("host"), sshPort = j.getInt("port"),
        sshUser = j.getString("username"), sshPassword = j.getString("password"), sshPin = j.getString("hostPin"),
        hostKeyType = j.getString("hostKeyType"), container = "test-container")
    private fun check(p: AgentProfile, stage: DiagnosticStage, code: DiagnosticCode) {
        val results = ConnectionService().diagnose(p, ConnectionOperation(), false)
        assertTrue("Expected $stage/$code; got ${DiagnosticReport.preview(results)}", results.any { it.stage == stage && it.code == code })
    }
    @Test fun terminalUsesImportedKeyRatherThanPassword() {
        val j = fixture(); val ins = InstrumentationRegistry.getInstrumentation(); val context = ins.targetContext
        val h = HostProfile(id = "m4-terminal-host", sshHost = j.getString("host"), sshPort = j.getInt("port"), sshUser = j.getString("username"),
            sshPassword = "deliberately-wrong-password", sshPin = j.getString("hostPin"), hostKeyType = "ssh-rsa", authMethod = "private_key", privateKeyPem = j.getString("privateKeyPem"))
        SettingsStore.saveHost(context, h)
        val a = AgentRecord(id = "m4-terminal-agent", hostId = h.id, container = "test-container")
        SettingsStore.saveAgent(context, a)
        var state=""
        val activity = ins.startActivitySync(android.content.Intent(context, TerminalActivity::class.java).putExtra("profile_id", a.id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as TerminalActivity
        try {
            // Access checks are asynchronous. Clicking before authorization is deliberately ignored.
            var ready=false; val readyDeadline=System.currentTimeMillis()+10000
            while(!ready && System.currentTimeMillis()<readyDeadline) {
                ins.runOnMainSync {ready=TerminalActivity::class.java.getDeclaredField("ready").apply {isAccessible=true}.getBoolean(activity)}
                Thread.sleep(25)
            }
            assertTrue("Terminal access check did not finish",ready)
            ins.runOnMainSync {
                val views=ArrayList<android.view.View>()
                activity.window.decorView.findViewsWithText(views,"Container shell",android.view.View.FIND_VIEWS_WITH_TEXT)
                views.filterIsInstance<android.widget.Button>().single().performClick()
            }
            val end = System.currentTimeMillis() + 15000
            var connected = false
            while (System.currentTimeMillis() < end && !connected) {
                ins.runOnMainSync {
                    val output = TerminalActivity::class.java.getDeclaredField("outputView").apply { isAccessible = true }.get(activity) as android.widget.TextView
                    state=output.text.toString(); connected = state.contains("[Connected:")
                }
                Thread.sleep(50)
            }
            assertTrue("Actual terminal failed key-authenticated connection; state=$state", connected)
        } finally { ins.runOnMainSync { activity.finish() }; SettingsStore.delete(context, a.id); SettingsStore.deleteHost(context, h.id) }
    }
    @Test fun passwordAndDockerSuccess() { val j = fixture(); check(profile(j), DiagnosticStage.CONTAINER, DiagnosticCode.OK) }
    @Test fun wrongPassword() { val j = fixture(); check(profile(j).copy(sshPassword = "wrong"), DiagnosticStage.AUTH, DiagnosticCode.AUTH_FAILED) }
    @Test fun wrongPinStopsBeforeAuth() { val j = fixture(); check(profile(j).copy(sshPin = "SHA256:" + "A".repeat(43)), DiagnosticStage.PIN, DiagnosticCode.PIN_MISMATCH) }
    @Test fun wrongTypeStopsBeforeAuth() { val j = fixture(); check(profile(j).copy(hostKeyType = "ecdsa-sha2-nistp256"), DiagnosticStage.HANDSHAKE, DiagnosticCode.HANDSHAKE_FAILED) }
    @Test fun unencryptedRsaKeyActualAuth() { val j = fixture(); check(profile(j).copy(authMethod = "private_key", privateKeyPem = j.getString("privateKeyPem"), sshPassword = ""), DiagnosticStage.AUTH, DiagnosticCode.OK) }
    @Test fun encryptedRsaKeyActualAuth() { val j = fixture(); check(profile(j).copy(authMethod = "private_key", privateKeyPem = j.getString("encryptedPrivateKeyPem"), keyPassphrase = j.getString("passphrase"), sshPassword = ""), DiagnosticStage.AUTH, DiagnosticCode.OK) }
    @Test fun wrongKey() { val j = fixture(); check(profile(j).copy(authMethod = "private_key", privateKeyPem = j.getString("wrongPrivateKeyPem")), DiagnosticStage.AUTH, DiagnosticCode.AUTH_FAILED) }
    @Test fun wrongPassphrase() { val j = fixture(); check(profile(j).copy(authMethod = "private_key", privateKeyPem = j.getString("encryptedPrivateKeyPem"), keyPassphrase = "wrong"), DiagnosticStage.AUTH, DiagnosticCode.KEY_PASSPHRASE) }
    @Test fun dockerPermissionDenied() { val j = fixture(); check(profile(j).copy(sshUser = "docker-denied"), DiagnosticStage.DOCKER, DiagnosticCode.DOCKER_DENIED) }
    @Test fun missingContainer() { val j = fixture(); check(profile(j).copy(container = "missing"), DiagnosticStage.CONTAINER, DiagnosticCode.CONTAINER_UNAVAILABLE) }
    @Test fun stalledHandshakeTimesOut() { val j = fixture(); check(profile(j).copy(sshPort = j.getInt("stallPort")), DiagnosticStage.HANDSHAKE, DiagnosticCode.TIMEOUT) }
    @Test fun unsupportedNegotiationFailsWithoutWeakAlgorithms() { val j = fixture(); check(profile(j).copy(sshPort = j.getInt("unsupportedPort")), DiagnosticStage.HANDSHAKE, DiagnosticCode.HANDSHAKE_FAILED) }
    @Test fun cancellationClosesAnInFlightHandshake() {
        val j = fixture(); val operation = ConnectionOperation()
        val reached = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        var code: DiagnosticCode? = null
        val worker = Thread {
            try { ConnectionService().connect(profile(j).copy(sshPort = j.getInt("stallPort")), operation) { if (it == DiagnosticStage.REACHABILITY) reached.countDown() } }
            catch (e: ConnectionFailure) { code = e.code }
            finally { finished.countDown() }
        }
        worker.start()
        try {
            assertTrue("Fixture never reached handshake", reached.await(5, java.util.concurrent.TimeUnit.SECONDS))
            operation.cancel()
            assertTrue("Cancellation did not close blocked handshake", finished.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(DiagnosticCode.CANCELLED, code)
        } finally { operation.cancel(); worker.join(5000) }
    }
    @Test fun explicitCancellationIsBounded() {
        val j = fixture(); val operation = ConnectionOperation(); operation.cancel()
        val results = ConnectionService().diagnose(profile(j), operation, false)
        assertEquals(DiagnosticCode.CANCELLED, results.last().code)
    }
}
