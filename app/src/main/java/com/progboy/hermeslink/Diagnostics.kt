package com.progboy.hermeslink

import java.io.InputStream
import java.io.ByteArrayOutputStream

enum class DiagnosticStage { VALIDATION, DNS, REACHABILITY, HANDSHAKE, PIN, AUTH, DOCKER, CONTAINER, DASHBOARD }
enum class DiagnosticCode(val action: String) {
    OK("Check passed; this does not prove other stages are ready."),
    INVALID("Review the saved settings and verified fingerprint."),
    DNS_FAILED("Hostname could not be resolved. Check spelling, DNS and Tailscale."),
    UNREACHABLE("Cannot reach the port. Check Tailscale, routing, firewall and SSH service."),
    TIMEOUT("Timed out. Check connectivity and service response, then retry."),
    CANCELLED("Cancelled. No changes were made to server settings."),
    HANDSHAKE_FAILED("SSH negotiation failed. Check server SSH policy; weak algorithms are not enabled."),
    PIN_MISMATCH("Host key differs. Verify key type and SHA256 fingerprint independently before replacing it."),
    AUTH_FAILED("SSH authentication rejected. Check the selected username and credential."),
    KEY_UNSUPPORTED("Use an RSA PKCS#1 PEM private key. OpenSSH, PKCS#8 and Ed25519 imports are not supported."),
    KEY_PASSPHRASE("Private key could not be decrypted or parsed. Check the RSA PEM file and passphrase."),
    DOCKER_DENIED("Docker is unavailable or permission was denied. Ask the administrator; this app changes no permissions."),
    CONTAINER_UNAVAILABLE("Container was not found or is not running. Check its exact name and state."),
    DASHBOARD_FAILED("HTTPS dashboard check failed. Check TLS, endpoint and service; browser login may still be required."),
    DASHBOARD_AUTH("Dashboard requires browser authentication. Open it in your browser."),
    INTERNAL("The check could not complete safely. Retry; no raw error details are exported.")
}
data class DiagnosticResult(val stage: DiagnosticStage, val code: DiagnosticCode, val elapsedMs: Long) {
    val action get() = code.action
}
object DiagnosticReport {
    // Construct, never redact: no free-form text or supplied version strings enter reports.
    fun preview(results: List<DiagnosticResult>) = "report_version=1\n" + results.joinToString("") {
        "stage=${it.stage.name} code=${it.code.name} elapsed_ms=${it.elapsedMs.coerceIn(0, 3600000)}\n"
    }
}
object FingerprintPolicy {
    val types = listOf("ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521")
    fun canConfirm(pin: String, type: String, independentlyVerified: Boolean) = independentlyVerified &&
        type in types && Regex("SHA256:[A-Za-z0-9+/]{43}=?").matches(pin)
}
object PrivateKeyImport {
    // JSch parses RSA fields but tolerates trailing ASN.1 data; require a complete two-prime PKCS#1 value.
    private fun validateDerEnvelope(bytes: ByteArray) {
        var offset = 0
        fun octet(): Int {
            require(offset < bytes.size) { DiagnosticCode.KEY_UNSUPPORTED.action }
            return bytes[offset++].toInt() and 0xff
        }
        fun length(): Int {
            val first = octet()
            if (first < 128) return first
            val count = first and 0x7f
            require(count in 1..3) { DiagnosticCode.KEY_UNSUPPORTED.action }
            var value = octet()
            require(value != 0) { DiagnosticCode.KEY_UNSUPPORTED.action }
            repeat(count - 1) { value = (value shl 8) or octet() }
            require(value >= 128) { DiagnosticCode.KEY_UNSUPPORTED.action }
            return value
        }
        require(octet() == 0x30) { DiagnosticCode.KEY_UNSUPPORTED.action }
        val sequenceLength = length()
        require(offset + sequenceLength == bytes.size) { DiagnosticCode.KEY_UNSUPPORTED.action }
        repeat(9) { field ->
            require(octet() == 0x02) { DiagnosticCode.KEY_UNSUPPORTED.action }
            val size = length()
            require(size > 0 && offset + size <= bytes.size) { DiagnosticCode.KEY_UNSUPPORTED.action }
            val first = bytes[offset].toInt() and 0xff
            if (field == 0) require(size == 1 && first == 0) { DiagnosticCode.KEY_UNSUPPORTED.action }
            else require(first < 128 && (first != 0 || (size > 1 && bytes[offset + 1] < 0))) { DiagnosticCode.KEY_UNSUPPORTED.action }
            offset += size
        }
        require(offset == bytes.size) { DiagnosticCode.KEY_UNSUPPORTED.action }
    }
    const val MAX_BYTES = 65536
    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(bytes.size() + n <= MAX_BYTES) { "Private key exceeds 64 KiB." }
            bytes.write(buffer, 0, n)
        }
        val text = bytes.toString("US-ASCII").trim()
        val lines = text.lines()
        require(lines.size >= 3 && lines.first() == "-----BEGIN RSA PRIVATE KEY-----" &&
            lines.last() == "-----END RSA PRIVATE KEY-----") { DiagnosticCode.KEY_UNSUPPORTED.action }
        var bodyStart = 1
        var blockSize: Int? = null
        if (lines.getOrNull(1) == "Proc-Type: 4,ENCRYPTED") {
            // Without a passphrase only the envelope is verifiable. ConnectionService must decrypt before use.
            val header = Regex("DEK-Info: (DES-EDE3-CBC|AES-128-CBC|AES-192-CBC|AES-256-CBC),([0-9A-Fa-f]+)")
                .matchEntire(lines.getOrNull(2).orEmpty())
            require(header != null && lines.getOrNull(3) == "") { DiagnosticCode.KEY_UNSUPPORTED.action }
            blockSize = if (header.groupValues[1] == "DES-EDE3-CBC") 8 else 16
            require(header.groupValues[2].length == blockSize * 2) { DiagnosticCode.KEY_UNSUPPORTED.action }
            bodyStart = 4
        }
        val body = lines.subList(bodyStart, lines.lastIndex)
        require(body.isNotEmpty() && body.all { it.isNotEmpty() && Regex("[A-Za-z0-9+/]+={0,2}").matches(it) }) {
            DiagnosticCode.KEY_UNSUPPORTED.action
        }
        val encoded = body.joinToString("")
        val decoded = try { java.util.Base64.getDecoder().decode(encoded) }
            catch (_: Exception) { throw IllegalArgumentException(DiagnosticCode.KEY_UNSUPPORTED.action) }
        try {
            require(java.util.Base64.getEncoder().encodeToString(decoded) == encoded) { DiagnosticCode.KEY_UNSUPPORTED.action }
            if (blockSize != null) require(decoded.isNotEmpty() && decoded.size % blockSize == 0) { DiagnosticCode.KEY_UNSUPPORTED.action }
            else validateDerEnvelope(decoded)
            val key = try { com.jcraft.jsch.KeyPair.load(com.jcraft.jsch.JSch(), text.toByteArray(Charsets.US_ASCII), null) }
                catch (_: Exception) { throw IllegalArgumentException(DiagnosticCode.KEY_UNSUPPORTED.action) }
            try {
                require(key.keyType == com.jcraft.jsch.KeyPair.RSA && key.isEncrypted == (blockSize != null)) { DiagnosticCode.KEY_UNSUPPORTED.action }
            } finally { key.dispose() }
        } finally { decoded.fill(0) }
        return text
    }
}
