package com.progboy.hermeslink

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** One monitor serializes migration and every credential read-modify-write.
 * profiles_v2 is encrypted recovery evidence ONLY, never an automatic fallback.
 * The first successful v3 mutation atomically removes this stale secret copy.
 * Corrupt/newer v3 must be recovered explicitly; it is never overwritten by v2.
 */
object SettingsStore {
    private fun prefs(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext, "hermeslink_secure",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    @Synchronized fun loadState(context: Context): HostAgentState {
        val p = prefs(context)
        check(!p.getBoolean("device_gate", false) || VaultSession.unlocked) { "Vault locked" }
        check(!p.contains("profiles_v4")) { "Unsupported settings version" }
        p.getString("profiles_v3", null)?.let { return ephemeral(decode(it)) }
        val source = p.getString("profiles_v2", null)
        val legacyHost = p.getString("host", null)
        val profiles = if (source != null) decodeV2(source) else if (legacyHost != null) listOf(
            ProfilePolicy.migrateLegacy(legacyHost, p.getInt("port", 0), p.getString("password", "").orEmpty(), p.getString("mode", "web").orEmpty())) else emptyList()
        val state = HostAgentState.migrate(profiles, if (source != null) p.getString("selected_v2", null) else profiles.firstOrNull()?.id)
        val edit = p.edit().putString("profiles_v3", encode(state))
        if (legacyHost != null && source == null) edit.putString("legacy_unassigned_password", p.getString("password", ""))
            .remove("host").remove("port").remove("password").remove("mode")
        check(edit.commit()) { "Could not persist migrated settings" }
        return state
    }
    private fun JSONObject.text(key: String): String = get(key).let { require(it is String) { "Invalid settings field" }; it }
    private fun JSONObject.optionalText(key: String): String = if (has(key)) text(key) else ""
    private fun JSONObject.integer(key: String): Int = get(key).let { require(it is Int) { "Invalid settings field" }; it }
    private fun decodeV2(json: String): List<AgentProfile> = JSONArray(json).let { array ->
        (0 until array.length()).map { i -> val o = array.getJSONObject(i)
            AgentProfile(o.text("id"), o.text("name"), o.text("provider"),
                o.optionalText("sshHost"), if (o.has("sshPort")) o.integer("sshPort") else 22, o.optionalText("sshUser"), o.optionalText("sshPassword"),
                o.optionalText("sshPin"), o.optionalText("container"), o.optionalText("webUrl"), o.optionalText("basicUser"), o.optionalText("basicPassword")) }
    }
    private fun encode(s: HostAgentState): String = JSONObject().apply {
        put("version", 3); put("selectedId", s.selectedId ?: JSONObject.NULL)
        put("hosts", JSONArray().apply { s.hosts.forEach { h -> put(JSONObject().apply {
            put("id", h.id); put("name", h.name); put("sshHost", h.sshHost); put("sshPort", h.sshPort)
            put("sshUser", h.sshUser); put("sshPassword", if (h.rememberPassword) h.sshPassword else ""); put("sshPin", h.sshPin)
            put("authMethod", h.authMethod); put("privateKeyPem", if (h.rememberKey) h.privateKeyPem else "")
            put("keyPassphrase", if (h.rememberPassphrase) h.keyPassphrase else "")
            put("rememberPassword", h.rememberPassword); put("rememberKey", h.rememberKey); put("rememberPassphrase", h.rememberPassphrase)
            put("hostKeyType", h.hostKeyType); put("pinVerified", h.pinVerified)
        }) } })
        put("agents", JSONArray().apply { s.agents.forEach { a -> put(JSONObject().apply {
            put("id", a.id); put("name", a.name); put("provider", a.provider); put("hostId", a.hostId)
            put("container", a.container); put("webUrl", a.webUrl); put("basicUser", a.basicUser)
            put("basicPassword", if (a.rememberCredential) a.basicPassword else ""); put("preferredAccess", a.preferredAccess)
            put("rememberCredential", a.rememberCredential)
        }) } })
    }.toString()
    private fun decode(json: String): HostAgentState {
        val o = JSONObject(json)
        require(o.integer("version") == 3) { "Unsupported settings version" }
        val hosts = o.getJSONArray("hosts"); val agents = o.getJSONArray("agents")
        return HostAgentState((0 until hosts.length()).map { i -> hosts.getJSONObject(i).let { h ->
            HostProfile(h.text("id"), h.text("name"), h.text("sshHost"), h.integer("sshPort"),
                h.text("sshUser"), h.text("sshPassword"), h.text("sshPin"),
                h.defaultText("authMethod", "password"), h.optionalText("privateKeyPem"), h.optionalText("keyPassphrase"),
                h.flag("rememberPassword", true), h.flag("rememberKey", true), h.flag("rememberPassphrase", false),
                h.optionalText("hostKeyType"), h.flag("pinVerified", false)) } },
            (0 until agents.length()).map { i -> agents.getJSONObject(i).let { a ->
                AgentRecord(a.text("id"), a.text("name"), a.text("provider"), a.text("hostId"),
                    a.text("container"), a.text("webUrl"), a.text("basicUser"), a.text("basicPassword"), a.text("preferredAccess"), a.flag("rememberCredential", true)) } },
            if (o.get("selectedId") == JSONObject.NULL) null else o.text("selectedId")).validate()
    }
    private fun JSONObject.defaultText(key: String, fallback: String) = if (has(key)) text(key) else fallback
    private fun JSONObject.flag(key: String, fallback: Boolean): Boolean = if (!has(key)) fallback else get(key).let { require(it is Boolean); it }
    private fun ephemeral(s: HostAgentState) = s.copy(
        hosts = s.hosts.map { h -> VaultSession.host(h.id)?.let { e -> h.copy(
            sshPassword = if (h.rememberPassword) h.sshPassword else e.sshPassword,
            privateKeyPem = if (h.rememberKey) h.privateKeyPem else e.privateKeyPem,
            keyPassphrase = if (h.rememberPassphrase) h.keyPassphrase else e.keyPassphrase) } ?: h },
        agents = s.agents.map { a -> if (a.rememberCredential) a else a.copy(basicPassword = VaultSession.agent(a.id)?.basicPassword.orEmpty()) })
    private fun persist(context: Context, state: HostAgentState) {
        check(prefs(context).edit().putString("profiles_v3", encode(state.validate()))
            .remove("profiles_v2").remove("selected_v2").commit()) { "Could not save encrypted settings" }
    }
    @Synchronized fun loadAll(context: Context): List<AgentProfile> = loadState(context).let { s -> s.agents.map { s.resolve(it.id)!! } }
    @Synchronized fun selectedId(context: Context): String? = loadState(context).selectedId
    @Synchronized fun select(context: Context, id: String) = persist(context, loadState(context).copy(selectedId = id))
    @Synchronized fun get(context: Context, id: String?): AgentProfile? = id?.let { loadState(context).resolve(it) }
    // Compatibility for the flat-profile callers; the manager uses saveHost/saveAgent.
    @Synchronized fun save(context: Context, profile: AgentProfile) = synchronized(VaultSession) {
        check(profile.sessionEpoch == VaultSession.epoch()) { "Session expired; reload before saving" }
        val s = loadState(context)
        val hostId = s.agents.find { it.id == profile.id }?.hostId ?: "legacy-host-${profile.id}"
        val previousHost = s.hosts.find { it.id == hostId }
        val h = (previousHost ?: HostProfile(id = hostId)).copy(name = previousHost?.name ?: profile.name,
            sshHost = profile.sshHost, sshPort = profile.sshPort, sshUser = profile.sshUser, sshPassword = profile.sshPassword, sshPin = profile.sshPin,
            authMethod = profile.authMethod, privateKeyPem = profile.privateKeyPem, keyPassphrase = profile.keyPassphrase, hostKeyType = profile.hostKeyType)
        val a = (s.agents.find { it.id == profile.id } ?: AgentRecord(id = profile.id, hostId = hostId)).copy(
            name = profile.name, provider = profile.provider, hostId = hostId, container = profile.container,
            webUrl = profile.webUrl, basicUser = profile.basicUser, basicPassword = profile.basicPassword)
        persist(context, s.copy(hosts = s.hosts.filterNot { it.id == hostId } + h,
            agents = s.agents.filterNot { it.id == a.id } + a, selectedId = a.id))
        VaultSession.remember(h, profile.sessionEpoch); VaultSession.remember(a, profile.sessionEpoch)
    }
    // Lock cannot advance the epoch between validation and the durable write.
    // Always acquire SettingsStore before VaultSession; lock dispatches listeners outside its monitor.
    @Synchronized fun saveHost(context: Context, host: HostProfile) = synchronized(VaultSession) {
        val epoch = host.sessionEpoch
        check(epoch == VaultSession.epoch()) { "Session expired; reload before saving" }
        require(host.name.isNotBlank()) { "Enter a host name." }
        require(host.sshPort in 1..65535) { "SSH port must be 1–65535." }
        val s = loadState(context)
        persist(context, s.copy(hosts = s.hosts.filterNot { it.id == host.id } + host))
        VaultSession.remember(host, epoch)
    }
    @Synchronized fun saveAgent(context: Context, agent: AgentRecord) = synchronized(VaultSession) {
        val epoch = agent.sessionEpoch
        check(epoch == VaultSession.epoch()) { "Session expired; reload before saving" }
        require(agent.name.isNotBlank()) { "Enter an agent name." }
        val s = loadState(context)
        require(s.hosts.any { it.id == agent.hostId }) { "Select a saved host first." }
        persist(context, s.copy(agents = s.agents.filterNot { it.id == agent.id } + agent, selectedId = agent.id))
        VaultSession.remember(agent, epoch)
    }
    @Synchronized fun deleteHost(context: Context, id: String) {
        val s = loadState(context)
        require(s.agents.none { it.hostId == id }) { "Host is used by saved agents. Reassign or delete those agents first, then delete this host." }
        persist(context, s.copy(hosts = s.hosts.filterNot { it.id == id }))
        VaultSession.forgetHost(id)
    }
    @Synchronized fun delete(context: Context, id: String) {
        val s = loadState(context); val agents = s.agents.filterNot { it.id == id }
        persist(context, s.copy(agents = agents, selectedId = s.selectedId?.takeIf { selected -> agents.any { it.id == selected } }))
        VaultSession.forgetAgent(id)
    }
    @Synchronized fun setGateEnabled(context: Context, enabled: Boolean) {
        loadState(context)
        check(!enabled || (context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure) { "Set a secure device screen lock first" }
        check(!enabled || VaultSession.unlocked) { "Confirm device authentication first" }
        check(prefs(context).edit().putBoolean("device_gate", enabled).commit())
    }
    @Synchronized fun gateEnabled(context: Context): Boolean = prefs(context).getBoolean("device_gate", false)
    @Synchronized fun forgetHostCredentials(context: Context, id: String) {
        val s = loadState(context)
        persist(context, s.copy(hosts = s.hosts.map { if (it.id == id) it.copy(sshPassword = "", privateKeyPem = "", keyPassphrase = "") else it }))
        VaultSession.forgetHost(id)
    }
    @Synchronized fun forgetAgentCredentials(context: Context, id: String) {
        val s = loadState(context)
        persist(context, s.copy(agents = s.agents.map { if (it.id == id) it.copy(basicPassword = "") else it }))
        VaultSession.forgetAgent(id)
    }
    @Synchronized fun forgetLegacySecret(context: Context) {
        loadState(context)
        check(prefs(context).edit().remove("legacy_unassigned_password").remove("profiles_v2").remove("selected_v2").commit())
    }
}
