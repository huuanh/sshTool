package com.example.termiusclone.ssh

import android.util.Log
import com.example.termiusclone.data.db.HostEntity
import com.example.termiusclone.data.db.IdentityEntity
import com.example.termiusclone.data.db.KnownHostDao
import com.example.termiusclone.data.db.KnownHostEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.OutputStream
import java.security.PublicKey

sealed class SshState {
    data object Idle : SshState()
    data object Connecting : SshState()
    data object Connected : SshState()
    data class Error(val message: String) : SshState()
    data object Disconnected : SshState()
    data class FingerprintPrompt(
        val host: String,
        val port: Int,
        val keyType: String,
        val fingerprint: String,
        val accept: () -> Unit,
        val reject: () -> Unit
    ) : SshState()
}

class SshConnection(
    private val host: HostEntity,
    private val identity: IdentityEntity?,
    private val knownHostDao: KnownHostDao,
    private val strictHostKey: Boolean,
    private val keepAliveSec: Int = 0,
    private val autoReconnect: Boolean = false
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ssh: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var inputJob: Job? = null
    private val forwarderThreads = mutableListOf<Thread>()
    private val localServerSockets = mutableListOf<java.net.ServerSocket>()
    @Volatile private var manuallyDisconnected = false
    @Volatile private var reconnectAttempt = 0

    private val _state = MutableStateFlow<SshState>(SshState.Idle)
    val state: StateFlow<SshState> = _state.asStateFlow()

    private val _output = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val output: SharedFlow<String> = _output.asSharedFlow()

    fun connect() {
        scope.launch {
            _state.value = SshState.Connecting
            try {
                val client = SSHClient()
                client.addHostKeyVerifier(buildVerifier())
                if (keepAliveSec > 0) {
                    try {
                        client.connection.keepAlive.keepAliveInterval = keepAliveSec
                    } catch (_: Throwable) { /* older sshj */ }
                }
                client.connect(host.hostname, host.port)

                SshAuth.authenticate(client, host, identity)

                val sess = client.startSession()
                // Use xterm-256color so bash/readline emits standard CSI sequences
                // for arrow keys (ESC [ A) instead of vt100 application-mode (ESC O A).
                sess.allocatePTY(
                    "xterm-256color",
                    120, 40,           // cols, rows
                    800, 600,          // width, height (px) — informational
                    emptyMap<net.schmizz.sshj.connection.channel.direct.PTYMode, Int>()
                )
                val sh = sess.startShell()

                ssh = client
                session = sess
                shell = sh
                _state.value = SshState.Connected
                reconnectAttempt = 0

                setupPortForwards(client)

                inputJob = scope.launch {
                    val buf = ByteArray(4096)
                    val stream = sh.inputStream
                    try {
                        while (true) {
                            val n = stream.read(buf)
                            if (n <= 0) break
                            _output.emit(String(buf, 0, n, Charsets.UTF_8))
                        }
                    } catch (_: Throwable) { /* connection closed */ }
                    _state.value = SshState.Disconnected
                    if (autoReconnect && !manuallyDisconnected) scheduleReconnect()
                }
            } catch (e: Throwable) {
                _state.value = SshState.Error(e.message ?: e::class.java.simpleName)
                cleanup()
                if (autoReconnect && !manuallyDisconnected) scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectAttempt++
        val delayMs = (1500L * reconnectAttempt).coerceAtMost(15_000L)
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (!manuallyDisconnected) connect()
        }
    }

    private fun setupPortForwards(client: SSHClient) {
        val forwards = PortForwardParser.parse(host.portForwards)
        if (forwards.isEmpty()) return
        for (fwd in forwards) {
            try {
                when (fwd) {
                    is PortForwardSpec.Local -> startLocalForward(client, fwd)
                    is PortForwardSpec.Remote -> startRemoteForward(client, fwd)
                }
            } catch (e: Throwable) {
                _output.tryEmit("\r\n[port-forward setup failed: ${e.message}]\r\n")
            }
        }
    }

    private fun startLocalForward(client: SSHClient, spec: PortForwardSpec.Local) {
        val socketAddr = java.net.InetSocketAddress("127.0.0.1", spec.localPort)
        val server = java.net.ServerSocket()
        server.reuseAddress = true
        server.bind(socketAddr)
        localServerSockets += server
        val t = Thread({
            try {
                val forwarder = client.newLocalPortForwarder(
                    net.schmizz.sshj.connection.channel.direct.Parameters(
                        "127.0.0.1", spec.localPort, spec.remoteHost, spec.remotePort
                    ),
                    server
                )
                forwarder.listen()
            } catch (e: Throwable) {
                Log.w("SshConnection", "local fwd ${spec.localPort} ended: ${e.message}")
            }
        }, "ssh-local-fwd-${spec.localPort}")
        t.isDaemon = true
        t.start()
        forwarderThreads += t
    }

    private fun startRemoteForward(client: SSHClient, spec: PortForwardSpec.Remote) {
        client.remotePortForwarder.bind(
            net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder.Forward(spec.remotePort),
            net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener(
                java.net.InetSocketAddress(spec.localHost, spec.localPort)
            )
        )
    }

    private fun buildVerifier(): HostKeyVerifier = object : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fp = net.schmizz.sshj.common.SecurityUtils.getFingerprint(key)
            val keyType = key.algorithm
            val existing = kotlinx.coroutines.runBlocking { knownHostDao.find(hostname, port) }
            if (existing != null) {
                return existing.fingerprint == fp
            }
            if (!strictHostKey) {
                kotlinx.coroutines.runBlocking {
                    knownHostDao.insert(KnownHostEntity(hostname, port, keyType, fp))
                }
                return true
            }
            return promptFingerprintBlocking(hostname, port, keyType, fp, key)
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    private fun promptFingerprintBlocking(
        host: String,
        port: Int,
        keyType: String,
        fingerprint: String,
        @Suppress("UNUSED_PARAMETER") key: PublicKey
    ): Boolean {
        val lock = Object()
        var decision: Boolean? = null
        _state.value = SshState.FingerprintPrompt(
            host = host,
            port = port,
            keyType = keyType,
            fingerprint = fingerprint,
            accept = {
                synchronized(lock) {
                    scope.launch {
                        knownHostDao.insert(KnownHostEntity(host, port, keyType, fingerprint))
                    }
                    decision = true
                    lock.notifyAll()
                }
            },
            reject = {
                synchronized(lock) {
                    decision = false
                    lock.notifyAll()
                }
            }
        )
        synchronized(lock) {
            while (decision == null) lock.wait()
        }
        if (decision != true) _state.value = SshState.Error("Fingerprint rejected")
        else _state.value = SshState.Connecting
        return decision == true
    }

    fun send(data: String) {
        scope.launch {
            try {
                val out: OutputStream? = shell?.outputStream
                out?.write(data.toByteArray(Charsets.UTF_8))
                out?.flush()
            } catch (e: Throwable) {
                _state.value = SshState.Error(e.message ?: "send error")
            }
        }
    }

    fun sendCtrlC() = send("\u0003")
    fun sendRaw(s: String) = send(s)

    fun disconnect() {
        manuallyDisconnected = true
        scope.launch { cleanup() }
    }

    private fun cleanup() {
        for (s in localServerSockets) try { s.close() } catch (_: Throwable) {}
        localServerSockets.clear()
        forwarderThreads.clear()
        try { shell?.close() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        try { ssh?.disconnect() } catch (_: Throwable) {}
        shell = null
        session = null
        ssh = null
        inputJob?.cancel()
        if (_state.value !is SshState.Error) _state.value = SshState.Disconnected
    }
}
