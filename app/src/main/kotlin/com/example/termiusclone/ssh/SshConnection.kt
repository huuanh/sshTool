package com.example.termiusclone.ssh

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
import net.schmizz.sshj.userauth.password.PasswordUtils
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
    private val strictHostKey: Boolean
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ssh: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var inputJob: Job? = null

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
                client.connect(host.hostname, host.port)

                val username = host.username.ifBlank { identity?.username ?: "root" }
                authenticate(client, username)

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

                inputJob = scope.launch {
                    val buf = ByteArray(4096)
                    val stream = sh.inputStream
                    while (true) {
                        val n = stream.read(buf)
                        if (n <= 0) break
                        _output.emit(String(buf, 0, n, Charsets.UTF_8))
                    }
                    _state.value = SshState.Disconnected
                }
            } catch (e: Throwable) {
                _state.value = SshState.Error(e.message ?: e::class.java.simpleName)
                cleanup()
            }
        }
    }

    private suspend fun authenticate(client: SSHClient, username: String) = withContext(Dispatchers.IO) {
        val key = identity?.privateKey?.takeIf { it.isNotBlank() }
        val pass = host.password?.takeIf { it.isNotBlank() } ?: identity?.password?.takeIf { it.isNotBlank() }

        if (key == null && pass == null) {
            error("No password or private key provided")
        }

        val errors = mutableListOf<String>()

        // 1) public key
        if (key != null) {
            try {
                val passphrase = identity?.passphrase
                // Use SSHClient.loadKeys which auto-detects format (PEM, OpenSSH v1, PuTTY...)
                val keyProvider = if (passphrase.isNullOrEmpty()) {
                    client.loadKeys(key, null, null)
                } else {
                    client.loadKeys(key, null, PasswordUtils.createOneOff(passphrase.toCharArray()))
                }
                client.authPublickey(username, keyProvider)
                return@withContext
            } catch (e: Throwable) {
                errors += "publickey: ${e.message ?: e::class.java.simpleName}"
            }
        }

        // 2) password
        if (pass != null) {
            try {
                client.authPassword(username, pass)
                return@withContext
            } catch (e: Throwable) {
                errors += "password: ${e.message ?: e::class.java.simpleName}"
            }

            // 3) keyboard-interactive (many servers disable plain password but accept this)
            try {
                val finder = object : net.schmizz.sshj.userauth.password.PasswordFinder {
                    override fun reqPassword(resource: net.schmizz.sshj.userauth.password.Resource<*>?): CharArray =
                        pass.toCharArray()
                    override fun shouldRetry(resource: net.schmizz.sshj.userauth.password.Resource<*>?): Boolean = false
                }
                val provider = net.schmizz.sshj.userauth.method.PasswordResponseProvider(finder)
                client.auth(username, net.schmizz.sshj.userauth.method.AuthKeyboardInteractive(provider))
                return@withContext
            } catch (e: Throwable) {
                errors += "keyboard-interactive: ${e.message ?: e::class.java.simpleName}"
            }
        }

        // Build a helpful error message
        val allowed = try {
            client.userAuth?.allowedMethods?.joinToString(",") ?: "unknown"
        } catch (_: Throwable) { "unknown" }

        val hint = buildString {
            if (key != null && pass == null) {
                append(" Hint: server rejected the key. Check that the matching public key is in ")
                append("~/.ssh/authorized_keys on the server, the username '")
                append(username)
                append("' is correct, or add a password as a fallback.")
            } else if (key == null && pass != null) {
                append(" Hint: server rejected the password. Check username '")
                append(username)
                append("' and password, or the server may require a key.")
            }
        }

        error("Authentication failed (user='$username', server allows: $allowed). Tried: ${errors.joinToString("; ")}.$hint")
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
        scope.launch { cleanup() }
    }

    private fun cleanup() {
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
