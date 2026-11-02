package com.progboy.hermeslink

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.*
import org.junit.Test

class ConnectionSecurityTest {
    @Test fun knownSha256KeyMatchesButDifferentKeyIsRejected() {
        // SHA256 of the public test vector "abc", encoded as OpenSSH fingerprint.
        val keys = PinnedHostKeys("SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0")
        assertEquals(HostKeyRepository.OK, keys.check("example", "abc".toByteArray()))
        assertEquals(HostKeyRepository.CHANGED, keys.check("example", "abd".toByteArray()))
        assertEquals(HostKeyRepository.CHANGED, keys.check("example", null))
    }

    @Test fun utf8CharactersSurviveNetworkChunkBoundaries() {
        val decoder = StreamTextDecoder()
        val bytes = "A🙂éZ".toByteArray(Charsets.UTF_8)
        val actual = bytes.map { decoder.append(byteArrayOf(it), 1) }.joinToString("")
        assertEquals("A🙂éZ", actual)
    }
}
