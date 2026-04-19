package com.sshtool.app.data.ssh

import com.jcraft.jsch.*
import com.sshtool.app.terminal.TerminalEmulator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

data class ActiveSession(
    val id: String,
    val connectionId: Long,
    val connectionName: String,
    val jschSession: Session,
    val channel: ChannelShell,
    val emulator: TerminalEmulator,
    val outputStream: OutputStream,
    val inputStream: InputStream,
    var readJob: Job? = null
)

data class HostKeyInfo(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val keyType: String
)

class SshManager {
    private val jsch = JSch()
    private val sessions = ConcurrentHashMap<String, ActiveSession>()
    private val _activeSessions = MutableStateFlow<List<ActiveSession>>(emptyList())
    val activeSessions: StateFlow<List<ActiveSession>> = _activeSessions

    private var hostKeyCallback: (suspend (HostKeyInfo) -> Boolean)? = null

    fun setHostKeyCallback(callback: (suspend (HostKeyInfo) -> Boolean)?) {
        hostKeyCallback = callback
    }

    suspend fun connect(
        sessionId: String,
        connectionId: Long,
        connectionName: String,
        host: String,
        port: Int,
        username: String,
        password: String? = null,
        privateKeyBytes: ByteArray? = null,
        passphrase: String? = null,
        rows: Int = 40,
        cols: Int = 120
    ): ActiveSession = withContext(Dispatchers.IO) {
        privateKeyBytes?.let { keyBytes ->
            val pass = passphrase?.toByteArray()
            jsch.addIdentity(
                "key_$connectionId",
                keyBytes,
                null,
                pass
            )
        }

        val session = jsch.getSession(username, host, port)

        password?.let { session.setPassword(it) }

        val config = Properties()
        config["StrictHostKeyChecking"] = "no"
        session.setConfig(config)
        session.setServerAliveInterval(15000)
        session.setServerAliveCountMax(3)
        session.timeout = 30000

        session.connect(30000)

        val channel = session.openChannel("shell") as ChannelShell
        channel.setPtyType("xterm-256color", cols, rows, cols * 8, rows * 16)

        val emulator = TerminalEmulator(rows, cols)
        emulator.title = connectionName

        channel.connect(10000)

        val outputStream = channel.outputStream
        val inputStream = channel.inputStream

        val activeSession = ActiveSession(
            id = sessionId,
            connectionId = connectionId,
            connectionName = connectionName,
            jschSession = session,
            channel = channel,
            emulator = emulator,
            outputStream = outputStream,
            inputStream = inputStream
        )

        sessions[sessionId] = activeSession
        updateSessionList()
        activeSession
    }

    fun startReading(sessionId: String, scope: CoroutineScope) {
        val session = sessions[sessionId] ?: return
        session.readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            try {
                while (isActive && session.channel.isConnected) {
                    val available = session.inputStream.available()
                    if (available > 0) {
                        val read = session.inputStream.read(buffer, 0, minOf(available, buffer.size))
                        if (read > 0) {
                            session.emulator.processBytes(buffer, 0, read)
                        }
                    } else {
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    session.emulator.appendText("\r\n[Connection closed: ${e.message}]\r\n")
                }
            }
        }
    }

    fun sendInput(sessionId: String, data: ByteArray) {
        val session = sessions[sessionId] ?: return
        try {
            session.outputStream.write(data)
            session.outputStream.flush()
        } catch (_: Exception) {}
    }

    fun sendInput(sessionId: String, text: String) {
        sendInput(sessionId, text.toByteArray())
    }

    fun resize(sessionId: String, rows: Int, cols: Int) {
        val session = sessions[sessionId] ?: return
        try {
            session.channel.setPtySize(cols, rows, cols * 8, rows * 16)
            session.emulator.resize(rows, cols)
        } catch (_: Exception) {}
    }

    fun disconnect(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        session.readJob?.cancel()
        try { session.channel.disconnect() } catch (_: Exception) {}
        try { session.jschSession.disconnect() } catch (_: Exception) {}
        updateSessionList()
    }

    fun disconnectAll() {
        sessions.keys.toList().forEach { disconnect(it) }
    }

    fun getSession(sessionId: String): ActiveSession? = sessions[sessionId]

    fun isConnected(sessionId: String): Boolean =
        sessions[sessionId]?.jschSession?.isConnected == true

    fun getSessionCount(): Int = sessions.size

    private fun updateSessionList() {
        _activeSessions.value = sessions.values.toList()
    }

    // SFTP operations
    fun openSftpChannel(sessionId: String): ChannelSftp? {
        val session = sessions[sessionId] ?: return null
        return try {
            val channel = session.jschSession.openChannel("sftp") as ChannelSftp
            channel.connect(10000)
            channel
        } catch (e: Exception) {
            null
        }
    }

    // Port Forwarding
    fun addLocalPortForward(sessionId: String, localPort: Int, remoteHost: String, remotePort: Int) {
        val session = sessions[sessionId] ?: return
        session.jschSession.setPortForwardingL(localPort, remoteHost, remotePort)
    }

    fun addRemotePortForward(sessionId: String, remotePort: Int, localHost: String, localPort: Int) {
        val session = sessions[sessionId] ?: return
        session.jschSession.setPortForwardingR(remotePort, localHost, localPort)
    }

    fun removeLocalPortForward(sessionId: String, localPort: Int) {
        val session = sessions[sessionId] ?: return
        session.jschSession.delPortForwardingL(localPort)
    }

    // Host Key Info
    fun getHostKeyInfo(sessionId: String): HostKeyInfo? {
        val session = sessions[sessionId] ?: return null
        val hostKey = session.jschSession.hostKey ?: return null
        return HostKeyInfo(
            host = session.jschSession.host,
            port = session.jschSession.port,
            fingerprint = hostKey.getFingerPrint(jsch),
            keyType = hostKey.type
        )
    }

    // Derive public key from private key
    fun derivePublicKey(privateKeyBytes: ByteArray, passphrase: String?): String? {
        return try {
            val keyPair = KeyPair.load(jsch, privateKeyBytes, null)
            if (passphrase != null) {
                keyPair.decrypt(passphrase.toByteArray())
            }
            val out = java.io.ByteArrayOutputStream()
            keyPair.writePublicKey(out, "imported")
            val result = out.toString()
            keyPair.dispose()
            result
        } catch (e: Exception) {
            null
        }
    }

    // Key Generation
    fun generateKeyPair(type: String = "RSA", bits: Int = 4096): Pair<ByteArray, ByteArray> {
        val keyType = when (type.uppercase()) {
            "ED25519" -> KeyPair.ED25519
            "ECDSA" -> KeyPair.ECDSA
            else -> KeyPair.RSA
        }
        val keyPair = KeyPair.genKeyPair(jsch, keyType, bits)
        val privateOut = java.io.ByteArrayOutputStream()
        val publicOut = java.io.ByteArrayOutputStream()
        keyPair.writePrivateKey(privateOut)
        keyPair.writePublicKey(publicOut, "sshtool-generated")
        keyPair.dispose()
        return Pair(privateOut.toByteArray(), publicOut.toByteArray())
    }
}
