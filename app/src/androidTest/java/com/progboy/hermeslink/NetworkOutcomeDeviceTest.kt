package com.progboy.hermeslink

import org.junit.Assert.*
import org.junit.Test

class NetworkOutcomeDeviceTest {
    private val base = AgentProfile(sshHost = "127.0.0.1", sshUser = "synthetic", sshPin = "SHA256:" + "A".repeat(43))
    @Test fun dnsAndReachabilityAreNotAuthenticationErrors() {
        val dns = ConnectionService(1500).diagnose(base.copy(sshHost = "256.256.256.256"), ConnectionOperation(), false).last()
        assertEquals(DiagnosticStage.DNS, dns.stage)
        assertTrue(dns.code in listOf(DiagnosticCode.DNS_FAILED, DiagnosticCode.TIMEOUT))
        val port = java.net.ServerSocket(0).use { it.localPort }
        val reachability = ConnectionService(1500).diagnose(base.copy(sshPort = port), ConnectionOperation(), false).last()
        assertEquals(DiagnosticStage.REACHABILITY, reachability.stage)
        assertEquals(DiagnosticCode.UNREACHABLE, reachability.code)
    }
    @Test fun dashboardRejectsUnsafeUrlAndDoesNotBypassTls() {
        val service = ConnectionService(1000)
        assertEquals(DiagnosticCode.INVALID, service.dashboard("http://127.0.0.1", ConnectionOperation()))
        assertEquals(DiagnosticCode.INVALID, service.dashboard("https://user:secret@example.invalid", ConnectionOperation()))
        val server = java.net.ServerSocket(0)
        val worker = Thread { try { server.accept().use { it.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray()) } } catch (_: Exception) { } }
        worker.start()
        try { assertEquals(DiagnosticCode.DASHBOARD_FAILED, service.dashboard("https://127.0.0.1:${server.localPort}", ConnectionOperation())) }
        finally { server.close(); worker.join(2000) }
    }
    @Test fun unsupportedKeyFormatIsActionableAndNeverEchoed() {
        val result = ConnectionService().diagnose(base.copy(authMethod = "private_key", privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nSENSITIVE\n-----END OPENSSH PRIVATE KEY-----"), ConnectionOperation(), false)
        assertEquals(DiagnosticCode.KEY_UNSUPPORTED, result.last().code)
        assertFalse(DiagnosticReport.preview(result).contains("SENSITIVE"))
    }
}
