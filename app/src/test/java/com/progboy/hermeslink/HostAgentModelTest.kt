package com.progboy.hermeslink

import org.junit.Assert.*
import org.junit.Test

class HostAgentModelTest {
    @Test fun migrationPreservesIdsAndAllCredentialsWithoutAddressDeduplication() {
        val a = AgentProfile(id = "m3-first", sshHost = "same", sshPort = 2222, sshUser = "root", sshPassword = "one", sshPin = "pin1", container = "first", basicUser = "web1", basicPassword = "secret1")
        val b = a.copy(id = "m3-second", sshPassword = "two", sshPin = "pin2", basicPassword = "secret2")
        val s = HostAgentState.migrate(listOf(a, b), b.id)
        assertEquals(2, s.hosts.size)
        assertNotEquals(s.agents[0].hostId, s.agents[1].hostId)
        assertEquals(a, s.resolve(a.id)); assertEquals(b, s.resolve(b.id))
        assertEquals(b.id, s.selectedId)
    }
    @Test fun resolutionUsesCurrentHostAndSeparateAgentCredentials() {
        val h = HostProfile(id = "m3-shared", sshPassword = "old")
        val a = AgentRecord(id = "m3-a", hostId = h.id, basicPassword = "web-a")
        val b = AgentRecord(id = "m3-b", hostId = h.id, basicPassword = "web-b")
        val state = HostAgentState(listOf(h.copy(sshPassword = "new")), listOf(a,b)).validate()
        assertEquals("new", state.resolve(a.id)!!.sshPassword)
        assertEquals("new", state.resolve(b.id)!!.sshPassword)
        assertEquals("web-a", state.resolve(a.id)!!.basicPassword)
        assertEquals("web-b", state.resolve(b.id)!!.basicPassword)
    }
    @Test fun duplicateIdsAndDanglingReferencesFailClosed() {
        val h = HostProfile(id = "m3-host")
        val a = AgentRecord(id = "m3-a", hostId = h.id)
        for (state in listOf(HostAgentState(listOf(h,h)), HostAgentState(listOf(h), listOf(a,a)),
            HostAgentState(emptyList(), listOf(a)), HostAgentState(listOf(h), listOf(a), "missing"))) {
            try { state.validate(); fail("Invalid state accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
