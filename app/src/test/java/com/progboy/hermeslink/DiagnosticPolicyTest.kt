package com.progboy.hermeslink

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class DiagnosticPolicyTest {
    @Test fun reportIsAnAllowlistNotExceptionRedaction() {
        val result = DiagnosticResult(DiagnosticStage.AUTH, DiagnosticCode.AUTH_FAILED, 12)
        assertEquals("report_version=1\nstage=AUTH code=AUTH_FAILED elapsed_ms=12\n", DiagnosticReport.preview(listOf(result)))
        assertFalse(result.action.contains("Exception"))
    }
    @Test fun keyImportRejectsUnsupportedAndOversizedInput() {
        for (text in listOf("-----BEGIN OPENSSH PRIVATE KEY-----\nsecret", "x".repeat(65537))) {
            try { PrivateKeyImport.read(ByteArrayInputStream(text.toByteArray())); fail("Input must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun fingerprintRequiresIndependentConfirmationAndType() {
        val pin = "SHA256:" + "A".repeat(43)
        assertFalse(FingerprintPolicy.canConfirm(pin, "ssh-rsa", false))
        assertFalse(FingerprintPolicy.canConfirm(pin, "unknown", true))
        assertTrue(FingerprintPolicy.canConfirm(pin, "ssh-rsa", true))
    }
}
