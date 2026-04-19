package com.sshtool.app.ui.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionFormScreen(
    connectionId: Long,
    onBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val isEditing = connectionId != -1L
    val keys by viewModel.keys.collectAsState()

    var name by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var authMethod by rememberSaveable { mutableStateOf("password") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var selectedKeyId by rememberSaveable { mutableStateOf<Long?>(null) }
    var loaded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(connectionId) {
        if (isEditing && !loaded) {
            viewModel.getById(connectionId)?.let { conn ->
                name = conn.name
                host = conn.host
                port = conn.port.toString()
                username = conn.username
                password = conn.password ?: ""
                authMethod = conn.authMethod
                selectedKeyId = conn.privateKeyId
                loaded = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Connection" else "Add Connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Connection Name") },
                placeholder = { Text("My Server") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                placeholder = { Text("192.168.1.1 or example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                placeholder = { Text("root") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Auth Method
            Text(
                "Authentication Method",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilterChip(
                    onClick = { authMethod = "password" },
                    label = { Text("Password") },
                    selected = authMethod == "password"
                )
                FilterChip(
                    onClick = { authMethod = "key" },
                    label = { Text("SSH Key") },
                    selected = authMethod == "key"
                )
            }

            if (authMethod == "password") {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = "Toggle password"
                            )
                        }
                    }
                )
            } else {
                // SSH Key Selector
                var keyDropdownExpanded by remember { mutableStateOf(false) }
                val selectedKey = keys.find { it.id == selectedKeyId }

                ExposedDropdownMenuBox(
                    expanded = keyDropdownExpanded,
                    onExpandedChange = { keyDropdownExpanded = !keyDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedKey?.let { "${it.name} (${it.type})" } ?: "Select SSH Key",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("SSH Key") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = keyDropdownExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = keyDropdownExpanded,
                        onDismissRequest = { keyDropdownExpanded = false }
                    ) {
                        if (keys.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No keys available. Generate one first.") },
                                onClick = { keyDropdownExpanded = false },
                                enabled = false
                            )
                        } else {
                            keys.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text("${key.name} (${key.type})") },
                                    onClick = {
                                        selectedKeyId = key.id
                                        keyDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    scope.launch {
                        viewModel.save(
                            id = connectionId,
                            name = name,
                            host = host,
                            port = port.toIntOrNull() ?: 22,
                            username = username,
                            authMethod = authMethod,
                            password = if (authMethod == "password") password else null,
                            privateKeyId = if (authMethod == "key") selectedKeyId else null
                        )
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = host.isNotBlank() && username.isNotBlank() &&
                    (authMethod == "password" || selectedKeyId != null)
            ) {
                Text("Save")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
