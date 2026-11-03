package com.progboy.hermeslink

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class PrivateKeyImportTest {
    private fun pem(type: Int = KeyPair.RSA, encrypted: Boolean = false): String {
        val key = KeyPair.genKeyPair(JSch(), type, 2048)
        return try {
            ByteArrayOutputStream().also {
                if (encrypted) key.writePrivateKey(it, "test-only-passphrase".toByteArray()) else key.writePrivateKey(it)
            }.toString("US-ASCII").trim()
        } finally { key.dispose() }
    }
    private fun reject(text: String) {
        try { PrivateKeyImport.read(text.byteInputStream()); fail("Malformed/unsupported key accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun acceptsValidUnencryptedRsa() {
        val text = pem()
        assertEquals(text, PrivateKeyImport.read(text.byteInputStream()))
    }
    @Test fun acceptsValidEncryptedRsaWithoutPassphrase() {
        val text = pem(encrypted = true)
        assertEquals(text, PrivateKeyImport.read(text.byteInputStream()))
    }
    @Test fun rejectsMalformedBodyWithValidMarkers() {
        reject("-----BEGIN RSA PRIVATE KEY-----\nnot-a-private-key\n-----END RSA PRIVATE KEY-----")
    }
    @Test fun rejectsTruncatedDerWithValidMarkers() {
        val lines = pem().lines()
        reject((listOf(lines.first()) + lines[1] + lines.last()).joinToString("\n"))
    }
    @Test fun rejectsRelabeledDsaKey() {
        reject(pem(KeyPair.DSA).replace("DSA PRIVATE KEY", "RSA PRIVATE KEY"))
    }
    @Test fun rejectsTrailingDerGarbageAndTruncatedDerTail() {
        val lines = pem().lines()
        val der = java.util.Base64.getDecoder().decode(lines.drop(1).dropLast(1).joinToString(""))
        for (invalid in listOf(der + byteArrayOf(0), der.copyOf(der.size - 1))) {
            reject(lines.first() + "\n" + java.util.Base64.getEncoder().encodeToString(invalid) + "\n" + lines.last())
        }
    }
    @Test fun rejectsExtraFieldsInsideRsaSequence() {
        val lines = pem().lines()
        val der = java.util.Base64.getDecoder().decode(lines.drop(1).dropLast(1).joinToString(""))
        val count = der[1].toInt() and 0x7f
        var size = 0
        for (i in 2 until 2 + count) size = (size shl 8) or (der[i].toInt() and 0xff)
        size += 2
        for (i in 1 + count downTo 2) { der[i] = size.toByte(); size = size ushr 8 }
        val invalid = der + byteArrayOf(5, 0)
        reject(lines.first() + "\n" + java.util.Base64.getEncoder().encodeToString(invalid) + "\n" + lines.last())
    }
    @Test fun rejectsNonExactPemMarkers() {
        reject(pem().replace("-----BEGIN RSA PRIVATE KEY-----", "-----BEGIN RSA PRIVATE KEY-----junk"))
    }
    @Test fun acceptsCrlfPem() {
        val text = pem().replace("\n", "\r\n")
        assertEquals(text, PrivateKeyImport.read(text.byteInputStream()))
    }
    @Test fun rejectsMalformedEncryptedEnvelope() {
        val text = pem(encrypted = true)
        reject(text.replace(Regex("DEK-Info: [^\\n]+"), "DEK-Info: AES-128-CBC,00"))
        reject(text.replace(Regex("DEK-Info: [^\\n]+"), "DEK-Info: UNKNOWN,00000000000000000000000000000000"))
        val lines = text.lines().toMutableList()
        val body = lines.indexOf("") + 1
        lines[body] = "AAAA"
        reject(lines.take(body + 1).plus(lines.last()).joinToString("\n"))
    }
}
