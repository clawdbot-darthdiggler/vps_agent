package com.progboy.hermeslink

/** In-process secrets are discarded on lock/process death; never put in saved state. */
object VaultSession {
    @Volatile var unlocked = false
        private set
    private val hosts = mutableMapOf<String, HostProfile>()
    private val agents = mutableMapOf<String, AgentRecord>()
    private val listeners = mutableSetOf<() -> Unit>()
    @Synchronized fun unlock() { unlocked = true }
    private var generation = 0L
    @Synchronized fun epoch(): Long = generation
    @Synchronized fun remember(host: HostProfile, epoch: Long = epoch()) { if (epoch == generation) hosts[host.id] = host }
    @Synchronized fun remember(agent: AgentRecord, epoch: Long = epoch()) { if (epoch == generation) agents[agent.id] = agent }
    @Synchronized fun host(id: String) = hosts[id]
    @Synchronized fun agent(id: String) = agents[id]
    @Synchronized fun forgetHost(id: String) { hosts.remove(id) }
    @Synchronized fun forgetAgent(id: String) { agents.remove(id) }
    @Synchronized fun observe(listener: () -> Unit) { listeners.add(listener) }
    @Synchronized fun unobserve(listener: () -> Unit) { listeners.remove(listener) }
    fun lock() {
        val callbacks = synchronized(this) { unlocked = false; generation++; hosts.clear(); agents.clear(); listeners.toList() }
        callbacks.forEach { it() }
    }
}
