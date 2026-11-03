package com.progboy.hermeslink

import java.net.URI
import java.util.UUID

// Do not log profiles: they contain secrets.
data class AgentProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New agent",
    val provider: String = "Hermes",
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "",
    val sshPassword: String = "",
    val sshPin: String = "",
    val container: String = "",
    val webUrl: String = "",
    val basicUser: String = "",
    val basicPassword: String = "",
    val authMethod: String = "password", val privateKeyPem: String = "", val keyPassphrase: String = "",
    val hostKeyType: String = ""
) {
    internal val sessionEpoch = VaultSession.epoch()
    override fun toString() = "AgentProfile(redacted)"
}

object ProfilePolicy {
    fun webError(url: String): String? = try {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            (uri.port != -1 && uri.port !in 1..65535)) "Use an HTTPS URL without embedded credentials." else null
    } catch (_: Exception) { "Enter a valid HTTPS URL." }

    fun sshError(p: AgentProfile): String? = when {
        p.sshHost.isBlank() || p.sshHost.any { it.isWhitespace() || it in "/@" } -> "Enter a VPS hostname or IP (no URL)."
        p.sshPort !in 1..65535 -> "SSH port must be 1–65535."
        p.sshUser.isBlank() || p.sshUser.any { it.isISOControl() } -> "Enter the VPS SSH username."
        !Regex("SHA256:[A-Za-z0-9+/]{43}=?").matches(p.sshPin) -> "Paste a SHA256 host key fingerprint verified outside this app."
        p.container.isNotEmpty() && dockerCommand(p.container) == null -> "Container name: letters, digits, underscore, dot or hyphen; start with a letter or digit."
        else -> null
    }

    fun dockerCommand(name: String): String? =
        if (Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}").matches(name)) "docker exec -i $name /bin/sh" else null

    fun migrateLegacy(host: String, port: Int, password: String, mode: String): AgentProfile = AgentProfile(
        name = "Imported — review settings", sshHost = host,
        webUrl = if (mode == "web") "https://$host:$port" else "",
        basicPassword = if (mode == "web") password else ""
    )
}

class BoundedOutput(private val limit: Int = 32768) {
    private val buffer = StringBuilder()
    fun clear() { buffer.setLength(0) }
    fun append(text: String): String {
        buffer.append(text.takeLast(limit))
        if (buffer.length > limit) buffer.delete(0, buffer.length - limit)
        return buffer.toString()
    }
}
