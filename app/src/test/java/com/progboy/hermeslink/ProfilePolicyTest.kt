package com.progboy.hermeslink

import org.junit.Assert.*
import org.junit.Test

class ProfilePolicyTest {
    @Test fun webRequiresHttpsAndNoEmbeddedCredentials() {
        assertNull(ProfilePolicy.webError("https://agent.example/dashboard"))
        listOf("http://agent.example", "https://u:p@agent.example", "javascript:alert(1)", "https://").forEach {
            assertNotNull(ProfilePolicy.webError(it))
        }
    }
    @Test fun sshRequiresVerifiedPinAndExplicitUser() {
        val valid = AgentProfile(sshHost = "100.64.0.1", sshUser = "alice", sshPin = "SHA256:" + "A".repeat(43))
        assertNull(ProfilePolicy.sshError(valid))
        assertNotNull(ProfilePolicy.sshError(valid.copy(sshPin = "")))
        assertNotNull(ProfilePolicy.sshError(valid.copy(sshUser = "")))
        assertNotNull(ProfilePolicy.sshError(valid.copy(sshPort = 0)))
    }
    @Test fun dockerNameCannotInjectCommands() {
        assertEquals("docker exec -i agent-1 /bin/sh", ProfilePolicy.dockerCommand("agent-1"))
        listOf("", "-x", "a; id", "a\nwhoami", "a$(id)", "a b", "a'c").forEach {
            assertNull(ProfilePolicy.dockerCommand(it))
        }
    }
    @Test fun legacySharedPasswordNeverBecomesSshPassword() {
        val migrated = ProfilePolicy.migrateLegacy("server", 8080, "old-secret", "terminal")
        assertEquals("server", migrated.sshHost)
        assertEquals(22, migrated.sshPort)
        assertEquals("", migrated.sshPassword)
        assertEquals("", migrated.basicPassword)
        assertEquals("", migrated.webUrl)
    }
    @Test fun legacyWebRequiresHttpsReviewAndKeepsOnlyWebSecret() {
        val migrated = ProfilePolicy.migrateLegacy("server", 8080, "old-secret", "web")
        assertEquals("https://server:8080", migrated.webUrl)
        assertEquals("", migrated.sshPassword)
        assertEquals("old-secret", migrated.basicPassword)
        assertEquals("", migrated.basicUser)
    }
    @Test fun retainedOutputIsBounded() {
        val output = BoundedOutput(8)
        output.append("123456")
        assertEquals("34567890", output.append("7890"))
        assertEquals("cdefghij", output.append("abcdefghij"))
    }
}
