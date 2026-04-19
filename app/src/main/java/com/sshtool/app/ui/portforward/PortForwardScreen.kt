package com.sshtool.app.ui.portforward

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshtool.app.data.db.PortForwardEntity
import com.sshtool.app.data.repository.PortForwardRepository
import com.sshtool.app.data.ssh.SshManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PortForwardViewModel @Inject constructor(
    private val portForwardRepository: PortForwardRepository,
    private val sshManager: SshManager
) : ViewModel() {

    private var connectionId: Long = 0
    private var sessionId: String = ""
    private var initialized = false

    private val _portForwards = MutableStateFlow<List<PortForwardEntity>>(emptyList())
    val portForwards: StateFlow<List<PortForwardEntity>> = _portForwards

    fun init(connectionId: Long, sessionId: String) {
        if (initialized) return
        initialized = true
        this.connectionId = connectionId
        this.sessionId = sessionId
        viewModelScope.launch {
            portForwardRepository.getByConnection(connectionId).collect {
                _portForwards.value = it
            }
        }
    }

    fun addPortForward(type: String, localPort: Int, remoteHost: String, remotePort: Int) {
        viewModelScope.launch {
            val pf = PortForwardEntity(
                connectionId = connectionId,
                type = type,
                localPort = localPort,
                remoteHost = remoteHost,
                remotePort = remotePort
            )
            portForwardRepository.insert(pf)
            applyPortForward(pf)
        }
    }

    fun removePortForward(pf: PortForwardEntity) {
        viewModelScope.launch {
            portForwardRepository.delete(pf)
            try {
                when (pf.type) {
                    "local" -> sshManager.removeLocalPortForward(sessionId, pf.localPort)
                }
            } catch (_: Exception) {}
        }
    }

    private fun applyPortForward(pf: PortForwardEntity) {
        try {
            when (pf.type) {
                "local" -> sshManager.addLocalPortForward(
                    sessionId, pf.localPort, pf.remoteHost, pf.remotePort
                )
                "remote" -> sshManager.addRemotePortForward(
                    sessionId, pf.remotePort, pf.remoteHost, pf.localPort
                )
            }
        } catch (_: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortForwardScreen(
    connectionId: Long,
    sessionId: String,
    onBack: () -> Unit,
    viewModel: PortForwardViewModel = hiltViewModel()
) {
    val portForwards by viewModel.portForwards.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(connectionId, sessionId) {
        viewModel.init(connectionId, sessionId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Port Forwarding") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Rule")
            }
        }
    ) { padding ->
        if (portForwards.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.SyncAlt,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No port forwarding rules.\nTap + to add one.")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(portForwards, key = { it.id }) { pf ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (pf.type == "local") Icons.Default.ArrowForward
                                else Icons.Default.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${pf.type.uppercase()} Forward",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "localhost:${pf.localPort} → ${pf.remoteHost}:${pf.remotePort}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.removePortForward(pf) }) {
                                Icon(
                                    Icons.Default.Delete, "Delete",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Port Forward Dialog
    if (showAddDialog) {
        var type by remember { mutableStateOf("local") }
        var localPort by remember { mutableStateOf("") }
        var remoteHost by remember { mutableStateOf("localhost") }
        var remotePort by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Port Forward") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Type", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            onClick = { type = "local" },
                            label = { Text("Local") },
                            selected = type == "local"
                        )
                        FilterChip(
                            onClick = { type = "remote" },
                            label = { Text("Remote") },
                            selected = type == "remote"
                        )
                        FilterChip(
                            onClick = { type = "dynamic" },
                            label = { Text("Dynamic") },
                            selected = type == "dynamic"
                        )
                    }
                    OutlinedTextField(
                        value = localPort,
                        onValueChange = { localPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Local Port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    if (type != "dynamic") {
                        OutlinedTextField(
                            value = remoteHost,
                            onValueChange = { remoteHost = it },
                            label = { Text("Remote Host") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = remotePort,
                            onValueChange = { remotePort = it.filter { c -> c.isDigit() } },
                            label = { Text("Remote Port") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val lp = localPort.toIntOrNull() ?: 0
                        val rp = remotePort.toIntOrNull() ?: 0
                        if (lp > 0) {
                            viewModel.addPortForward(type, lp, remoteHost, rp)
                            showAddDialog = false
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}
