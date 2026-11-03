package com.progboy.hermeslink

import java.util.UUID

// Secret-bearing records deliberately redact their diagnostic representation.
data class HostProfile(val id: String = UUID.randomUUID().toString(), val name: String = "New host",
    val sshHost: String = "", val sshPort: Int = 22, val sshUser: String = "",
    val sshPassword: String = "", val sshPin: String = "") {
    override fun toString() = "HostProfile(redacted)"
}
data class AgentRecord(val id: String = UUID.randomUUID().toString(), val name: String = "New agent",
    val provider: String = "Hermes", val hostId: String, val container: String = "",
    val webUrl: String = "", val basicUser: String = "", val basicPassword: String = "",
    val preferredAccess: String = "Browser") {
    override fun toString() = "AgentRecord(redacted)"
}
data class HostAgentState(val hosts: List<HostProfile> = emptyList(), val agents: List<AgentRecord> = emptyList(),
    val selectedId: String? = null) {
    fun validate(): HostAgentState {
        require(hosts.all { it.id.isNotBlank() } && hosts.map { it.id }.distinct().size == hosts.size)
        require(agents.all { it.id.isNotBlank() && hosts.any { h -> h.id == it.hostId } } && agents.map { it.id }.distinct().size == agents.size)
        require(selectedId == null || agents.any { it.id == selectedId })
        return this
    }
    fun resolve(id: String): AgentProfile? {
        val a = agents.find { it.id == id } ?: return null
        val h = hosts.single { it.id == a.hostId }
        return AgentProfile(a.id, a.name, a.provider, h.sshHost, h.sshPort, h.sshUser,
            h.sshPassword, h.sshPin, a.container, a.webUrl, a.basicUser, a.basicPassword)
    }
    companion object {
        fun migrate(profiles: List<AgentProfile>, selected: String?) = HostAgentState(
            profiles.map { HostProfile("legacy-host-${it.id}", it.name, it.sshHost, it.sshPort, it.sshUser, it.sshPassword, it.sshPin) },
            profiles.map { AgentRecord(it.id, it.name, it.provider, "legacy-host-${it.id}", it.container, it.webUrl, it.basicUser, it.basicPassword) },
            selected?.takeIf { id -> profiles.any { it.id == id } }).validate()
    }
}
