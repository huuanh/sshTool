package com.sshtool.app.ui.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshtool.app.data.preferences.AppPreferences
import com.sshtool.app.data.repository.ConnectionRepository
import com.sshtool.app.data.repository.KnownHostRepository
import com.sshtool.app.data.repository.SshKeyRepository
import com.sshtool.app.data.security.CryptoManager
import com.sshtool.app.data.ssh.ActiveSession
import com.sshtool.app.data.ssh.SshManager
import com.sshtool.app.service.SshSessionService
import com.sshtool.app.terminal.ScreenSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed class TerminalUiState {
    data object Idle : TerminalUiState()
    data object Connecting : TerminalUiState()
    data class Connected(val sessionId: String) : TerminalUiState()
    data class Error(val message: String) : TerminalUiState()
    data class HostKeyChanged(
        val host: String,
        val port: Int,
        val oldFingerprint: String,
        val newFingerprint: String,
        val keyType: String
    ) : TerminalUiState()
}

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val sshManager: SshManager,
    private val connectionRepository: ConnectionRepository,
    private val sshKeyRepository: SshKeyRepository,
    private val knownHostRepository: KnownHostRepository,
    private val cryptoManager: CryptoManager,
    private val appPreferences: AppPreferences,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<TerminalUiState>(TerminalUiState.Idle)
    val uiState: StateFlow<TerminalUiState> = _uiState

    private val _snapshot = MutableStateFlow<ScreenSnapshot?>(null)
    val snapshot: StateFlow<ScreenSnapshot?> = _snapshot

    private var currentSessionId: String? = null
    private var pendingConnectionId: Long = -1L
    private var snapshotJob: Job? = null

    val activeSessions: StateFlow<List<ActiveSession>> = sshManager.activeSessions

    val settings = appPreferences.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        com.sshtool.app.data.preferences.AppSettings()
    )

    fun connect(connectionId: Long) {
        pendingConnectionId = connectionId
        viewModelScope.launch {
            _uiState.value = TerminalUiState.Connecting
            try {
                val connection = connectionRepository.getById(connectionId) ?: run {
                    _uiState.value = TerminalUiState.Error("Connection not found")
                    return@launch
                }

                // Resolve private key if using key auth
                var privateKeyBytes: ByteArray? = null
                var keyPassphrase: String? = null
                if (connection.authMethod == "key" && connection.privateKeyId != null) {
                    val sshKey = sshKeyRepository.getById(connection.privateKeyId)
                    if (sshKey != null) {
                        privateKeyBytes = cryptoManager.decrypt(sshKey.privateKey).toByteArray()
                        keyPassphrase = sshKey.passphrase?.let { cryptoManager.decrypt(it) }
                    } else {
                        _uiState.value = TerminalUiState.Error("SSH key not found. Please update the connection.")
                        return@launch
                    }
                }

                val sessionId = UUID.randomUUID().toString()
                val settings = appPreferences.settings.first()

                val decryptedPassword = connection.password?.let { cryptoManager.decrypt(it) }

                val session = sshManager.connect(
                    sessionId = sessionId,
                    connectionId = connectionId,
                    connectionName = connection.name,
                    host = connection.host,
                    port = connection.port,
                    username = connection.username,
                    password = decryptedPassword,
                    privateKeyBytes = privateKeyBytes,
                    passphrase = keyPassphrase,
                    rows = settings.defaultRows,
                    cols = settings.defaultCols
                )

                // Verify host key against known hosts
                val hostKeyInfo = sshManager.getHostKeyInfo(sessionId)
                if (hostKeyInfo != null) {
                    val knownHost = knownHostRepository.get(connection.host, connection.port)
                    if (knownHost != null && knownHost.fingerprint != hostKeyInfo.fingerprint) {
                        // Host key changed - disconnect and warn user
                        sshManager.disconnect(sessionId)
                        _uiState.value = TerminalUiState.HostKeyChanged(
                            host = connection.host,
                            port = connection.port,
                            oldFingerprint = knownHost.fingerprint,
                            newFingerprint = hostKeyInfo.fingerprint,
                            keyType = hostKeyInfo.keyType
                        )
                        return@launch
                    }
                    // Save/update known host
                    knownHostRepository.save(
                        connection.host,
                        connection.port,
                        hostKeyInfo.fingerprint,
                        hostKeyInfo.keyType
                    )
                }

                currentSessionId = sessionId
                connectionRepository.updateLastConnected(connectionId)

                // Start reading SSH output
                sshManager.startReading(sessionId, viewModelScope)

                // Observe terminal updates
                snapshotJob?.cancel()
                snapshotJob = viewModelScope.launch {
                    session.emulator.version.collect {
                        _snapshot.value = session.emulator.getSnapshot()
                    }
                }

                // Start foreground service
                SshSessionService.start(appContext)

                _uiState.value = TerminalUiState.Connected(sessionId)
            } catch (e: Exception) {
                _uiState.value = TerminalUiState.Error(e.message ?: "Connection failed")
            }
        }
    }

    fun sendInput(text: String) {
        currentSessionId?.let { sshManager.sendInput(it, text) }
    }

    fun sendBytes(data: ByteArray) {
        currentSessionId?.let { sshManager.sendInput(it, data) }
    }

    fun sendSpecialKey(key: SpecialKey) {
        val bytes = when (key) {
            SpecialKey.ENTER -> "\r"
            SpecialKey.TAB -> "\t"
            SpecialKey.ESCAPE -> "\u001B"
            SpecialKey.BACKSPACE -> "\u007F"
            SpecialKey.DELETE -> "\u001B[3~"
            SpecialKey.UP -> "\u001B[A"
            SpecialKey.DOWN -> "\u001B[B"
            SpecialKey.RIGHT -> "\u001B[C"
            SpecialKey.LEFT -> "\u001B[D"
            SpecialKey.HOME -> "\u001B[H"
            SpecialKey.END -> "\u001B[F"
            SpecialKey.PAGE_UP -> "\u001B[5~"
            SpecialKey.PAGE_DOWN -> "\u001B[6~"
            SpecialKey.INSERT -> "\u001B[2~"
            SpecialKey.F1 -> "\u001BOP"
            SpecialKey.F2 -> "\u001BOQ"
            SpecialKey.F3 -> "\u001BOR"
            SpecialKey.F4 -> "\u001BOS"
            SpecialKey.F5 -> "\u001B[15~"
            SpecialKey.F6 -> "\u001B[17~"
            SpecialKey.F7 -> "\u001B[18~"
            SpecialKey.F8 -> "\u001B[19~"
            SpecialKey.F9 -> "\u001B[20~"
            SpecialKey.F10 -> "\u001B[21~"
            SpecialKey.F11 -> "\u001B[23~"
            SpecialKey.F12 -> "\u001B[24~"
        }
        sendInput(bytes)
    }

    fun sendCtrlKey(c: Char) {
        val code = c.uppercaseChar().code - 64
        if (code in 0..31) {
            sendBytes(byteArrayOf(code.toByte()))
        }
    }

    fun resize(rows: Int, cols: Int) {
        currentSessionId?.let { sshManager.resize(it, rows, cols) }
    }

    fun disconnect() {
        currentSessionId?.let { sessionId ->
            sshManager.disconnect(sessionId)
            currentSessionId = null
            _snapshot.value = null
            _uiState.value = TerminalUiState.Idle

            if (sshManager.getSessionCount() == 0) {
                SshSessionService.stop(appContext)
            }
        }
    }

    fun switchSession(sessionId: String) {
        val session = sshManager.getSession(sessionId) ?: return
        currentSessionId = sessionId
        snapshotJob?.cancel()
        snapshotJob = viewModelScope.launch {
            session.emulator.version.collect {
                _snapshot.value = session.emulator.getSnapshot()
            }
        }
        _uiState.value = TerminalUiState.Connected(sessionId)
    }

    fun getCurrentSessionId(): String? = currentSessionId

    fun acceptHostKeyAndReconnect() {
        val state = _uiState.value
        if (state is TerminalUiState.HostKeyChanged) {
            viewModelScope.launch {
                knownHostRepository.save(
                    state.host, state.port, state.newFingerprint, state.keyType
                )
                connect(pendingConnectionId)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Don't disconnect - sessions persist in SshManager / foreground service
    }
}

enum class SpecialKey {
    ENTER, TAB, ESCAPE, BACKSPACE, DELETE,
    UP, DOWN, RIGHT, LEFT,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
}
