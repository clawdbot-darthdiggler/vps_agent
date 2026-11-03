package com.progboy.hermeslink

import com.jcraft.jsch.*
import java.net.*
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.*
import javax.net.ssl.HttpsURLConnection

/** Cancellation owns sockets and sessions, not just the coroutine waiting for them. */
class ConnectionOperation {
    private val closers = mutableListOf<() -> Unit>()
    @Volatile var cancelled = false; private set
    @Volatile var timedOut = false; private set
    @Synchronized fun own(close: () -> Unit) { if (cancelled) { close(); check() } else closers.add(close) }
    fun check() { if (cancelled) throw ConnectionFailure(DiagnosticStage.HANDSHAKE, if (timedOut) DiagnosticCode.TIMEOUT else DiagnosticCode.CANCELLED) }
    fun cancel(timeout: Boolean = false) {
        val pending = synchronized(this) { timedOut = timedOut || timeout; cancelled = true; closers.toList().also { closers.clear() } }
        pending.forEach { try { it() } catch (_: Exception) { } }
    }
}
class ConnectionFailure(val stage: DiagnosticStage, val code: DiagnosticCode) : Exception(code.name)

/** Shared production SSH authentication/pinning service; no Activity, logging or raw error strings. */
class ConnectionService(private val timeoutMs: Int = 4000) {
    companion object {
        private val dns = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
            ThreadFactory { r -> Thread(r, "bounded-dns").apply { isDaemon = true } })
        private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "connection-deadline").apply { isDaemon = true } }
    }
    private fun resolve(host: String, op: ConnectionOperation): InetAddress {
        op.check()
        val future = try { dns.submit<InetAddress> { InetAddress.getAllByName(host).first() } }
        catch (_: RejectedExecutionException) { throw ConnectionFailure(DiagnosticStage.DNS, DiagnosticCode.TIMEOUT) }
        op.own { future.cancel(true) }
        try { return future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { throw ConnectionFailure(DiagnosticStage.DNS, DiagnosticCode.TIMEOUT) }
        catch (_: Exception) { op.check(); throw ConnectionFailure(DiagnosticStage.DNS, DiagnosticCode.DNS_FAILED) }
        finally { future.cancel(true) }
    }
    fun connect(p: AgentProfile, op: ConnectionOperation, progress: (DiagnosticStage) -> Unit = {}): Session {
        op.check()
        if (ProfilePolicy.sshError(p) != null || p.authMethod !in listOf("password", "private_key") ||
            (p.hostKeyType.isNotEmpty() && p.hostKeyType !in FingerprintPolicy.types))
            throw ConnectionFailure(DiagnosticStage.VALIDATION, DiagnosticCode.INVALID)
        progress(DiagnosticStage.VALIDATION)
        val address = resolve(p.sshHost, op); progress(DiagnosticStage.DNS)
        val repo = PinnedHostKeys(p.sshPin, p.hostKeyType)
        val jsch = JSch().apply { setHostKeyRepository(repo) }
        if (p.authMethod == "private_key") {
            try { PrivateKeyImport.read(p.privateKeyPem.byteInputStream()) }
            catch (_: Exception) { throw ConnectionFailure(DiagnosticStage.AUTH, DiagnosticCode.KEY_UNSUPPORTED) }
            val key = try { KeyPair.load(jsch, p.privateKeyPem.toByteArray(), null) }
            catch (_: Exception) { throw ConnectionFailure(DiagnosticStage.AUTH, DiagnosticCode.KEY_PASSPHRASE) }
            try {
                if (key.keyType != KeyPair.RSA) throw ConnectionFailure(DiagnosticStage.AUTH, DiagnosticCode.KEY_UNSUPPORTED)
                if (key.isEncrypted && !key.decrypt(p.keyPassphrase)) throw ConnectionFailure(DiagnosticStage.AUTH, DiagnosticCode.KEY_PASSPHRASE)
                jsch.addIdentity("imported-rsa", p.privateKeyPem.toByteArray(), null, p.keyPassphrase.toByteArray())
            } catch (e: ConnectionFailure) { throw e }
            catch (_: Exception) { throw ConnectionFailure(DiagnosticStage.AUTH, DiagnosticCode.KEY_PASSPHRASE) }
            finally { key.dispose() }
        }
        val ssh = jsch.getSession(p.sshUser, p.sshHost, p.sshPort)
        op.own { ssh.disconnect(); jsch.removeAllIdentity() }
        var reachable = false
        ssh.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                val socket = Socket(); op.own { socket.close() }
                try { socket.connect(InetSocketAddress(address, port), timeoutMs) }
                catch (_: SocketTimeoutException) { throw ConnectionFailure(DiagnosticStage.REACHABILITY, DiagnosticCode.TIMEOUT) }
                catch (_: Exception) { op.check(); throw ConnectionFailure(DiagnosticStage.REACHABILITY, DiagnosticCode.UNREACHABLE) }
                reachable = true; progress(DiagnosticStage.REACHABILITY)
                return socket
            }
            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })
        ssh.setConfig("StrictHostKeyChecking", "yes")
        ssh.setConfig("PreferredAuthentications", if (p.authMethod == "private_key") "publickey" else "password")
        // RSA key blob type is ssh-rsa, but signatures MUST use SHA-2, never ssh-rsa/SHA-1.
        if (p.hostKeyType.isNotEmpty()) ssh.setConfig("server_host_key", if (p.hostKeyType == "ssh-rsa") "rsa-sha2-512,rsa-sha2-256" else p.hostKeyType)
        if (p.authMethod == "password") ssh.setPassword(p.sshPassword)
        ssh.setServerAliveInterval(15000); ssh.setServerAliveCountMax(2)
        try {
            ssh.connect(timeoutMs); op.check()
            progress(DiagnosticStage.HANDSHAKE); progress(DiagnosticStage.PIN); progress(DiagnosticStage.AUTH)
            return ssh
        } catch (e: Exception) {
            ssh.disconnect(); jsch.removeAllIdentity()
            op.check()
            // Raw library text is used only for classification, never displayed, retained or exported.
            val message = e.message.orEmpty()
            val failure = when {
                repo.mismatch -> ConnectionFailure(DiagnosticStage.PIN, DiagnosticCode.PIN_MISMATCH)
                e is ConnectionFailure -> e
                !reachable -> ConnectionFailure(DiagnosticStage.REACHABILITY, DiagnosticCode.UNREACHABLE)
                message.contains("Auth fail", true) || message.contains("Auth cancel", true) -> ConnectionFailure(DiagnosticStage.AUTH, DiagnosticCode.AUTH_FAILED)
                e is SocketTimeoutException || message.contains("timeout", true) || message.contains("timed out", true) -> ConnectionFailure(DiagnosticStage.HANDSHAKE, DiagnosticCode.TIMEOUT)
                else -> ConnectionFailure(DiagnosticStage.HANDSHAKE, DiagnosticCode.HANDSHAKE_FAILED)
            }
            throw failure
        }
    }
    private fun exec(ssh: Session, command: String, op: ConnectionOperation): Pair<Int, String> {
        val ch = ssh.openChannel("exec") as ChannelExec
        op.own { ch.disconnect() }
        ch.setCommand(command); ch.setPty(false)
        val out = ch.inputStream; val err = ch.extInputStream
        val capture = StringBuilder(); val bytes = ByteArray(1024)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        try {
            ch.connect(timeoutMs)
            while (true) {
                op.check()
                if (System.nanoTime() > deadline) throw ConnectionFailure(DiagnosticStage.DOCKER, DiagnosticCode.TIMEOUT)
                for (stream in listOf(out, err)) {
                    val n = stream.available()
                    if (n > 0) {
                        val read = stream.read(bytes, 0, minOf(n, bytes.size))
                        if (stream === out && read > 0 && capture.length < 64) capture.append(String(bytes, 0, minOf(read, 64 - capture.length), Charsets.US_ASCII))
                    }
                }
                if (ch.isClosed && out.available() == 0 && err.available() == 0) return ch.exitStatus to capture.toString().trim()
                Thread.sleep(10)
            }
        } finally { ch.disconnect() }
    }
    fun diagnose(p: AgentProfile, operation: ConnectionOperation, dashboard: Boolean = true): List<DiagnosticResult> {
        val results = mutableListOf<DiagnosticResult>(); val start = System.nanoTime()
        fun record(stage: DiagnosticStage, code: DiagnosticCode) { results.add(DiagnosticResult(stage, code, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))) }
        val deadline = timer.schedule({ operation.cancel(true) }, 25, TimeUnit.SECONDS)
        var stage = DiagnosticStage.VALIDATION
        try {
            val ssh = connect(p, operation) { record(it, DiagnosticCode.OK) }
            if (p.container.isNotEmpty()) {
                stage = DiagnosticStage.DOCKER
                val docker = exec(ssh, "docker info", operation)
                if (docker.first != 0) throw ConnectionFailure(stage, DiagnosticCode.DOCKER_DENIED)
                record(stage, DiagnosticCode.OK)
                stage = DiagnosticStage.CONTAINER
                val container = exec(ssh, "docker inspect --format '{{.State.Running}}' ${p.container}", operation)
                if (container.first != 0 || container.second != "true") throw ConnectionFailure(stage, DiagnosticCode.CONTAINER_UNAVAILABLE)
                record(stage, DiagnosticCode.OK)
            }
        } catch (e: ConnectionFailure) { record(e.stage, e.code) }
        catch (_: Exception) { record(stage, if (operation.cancelled) { if (operation.timedOut) DiagnosticCode.TIMEOUT else DiagnosticCode.CANCELLED } else DiagnosticCode.INTERNAL) }
        if (dashboard && p.webUrl.isNotEmpty() && !operation.cancelled) {
            record(DiagnosticStage.DASHBOARD, dashboard(p.webUrl, operation))
        }
        deadline.cancel(false); operation.cancel()
        return results
    }
    fun dashboard(url: String, op: ConnectionOperation): DiagnosticCode {
        if (ProfilePolicy.webError(url) != null) return DiagnosticCode.INVALID
        var c: HttpsURLConnection? = null
        return try {
            op.check()
            c = URL(url).openConnection() as HttpsURLConnection
            val active = c; op.own { active.disconnect() }
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"; c.useCaches = false
            when (c.responseCode) { in 200..399 -> DiagnosticCode.OK; 401, 403 -> DiagnosticCode.DASHBOARD_AUTH; else -> DiagnosticCode.DASHBOARD_FAILED }
        } catch (_: Exception) { if (op.cancelled) DiagnosticCode.CANCELLED else DiagnosticCode.DASHBOARD_FAILED }
        finally { c?.disconnect() }
    }
}
