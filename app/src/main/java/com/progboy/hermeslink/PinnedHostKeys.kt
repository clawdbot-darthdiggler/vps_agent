package com.progboy.hermeslink

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64

/** Fail closed: only the explicitly configured, out-of-band verified key is accepted. */
class PinnedHostKeys(private val pin: String, private val keyType: String = "") : HostKeyRepository {
    @Volatile var mismatch = false; private set
    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) { mismatch = true; return HostKeyRepository.CHANGED }
        if (keyType.isNotEmpty() && try { HostKey(host ?: "", key).type != keyType } catch (_: Exception) { true }) {
            mismatch = true; return HostKeyRepository.CHANGED
        }
        val actual = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        return if (MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), pin.trimEnd('=').toByteArray(Charsets.US_ASCII)))
            HostKeyRepository.OK else { mismatch = true; HostKeyRepository.CHANGED }
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "Explicit SHA256 pin"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
