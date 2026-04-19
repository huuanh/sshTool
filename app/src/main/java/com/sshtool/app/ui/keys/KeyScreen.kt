package com.sshtool.app.ui.keys

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshtool.app.data.db.SshKeyEntity
import com.sshtool.app.data.repository.SshKeyRepository
import com.sshtool.app.data.security.CryptoManager
import com.sshtool.app.data.ssh.SshManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class KeyViewModel @Inject constructor(
    private val repository: SshKeyRepository,
    private val sshManager: SshManager,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    val keys: StateFlow<List<SshKeyEntity>> = repository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun generateKey(name: String, type: String, bits: Int) {
        viewModelScope.launch {
            try {
                val (privateKey, publicKey) = sshManager.generateKeyPair(type, bits)
                repository.insert(
                    SshKeyEntity(
                        name = name,
                        type = type,
                        publicKey = String(publicKey),
                        privateKey = cryptoManager.encrypt(String(privateKey))
                    )
                )
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun importKey(name: String, privateKeyPem: String, passphrase: String?) {
        viewModelScope.launch {
            try {
                val keyType = detectKeyType(privateKeyPem)
                val publicKey = sshManager.derivePublicKey(
                    privateKeyPem.toByteArray(),
                    passphrase
                ) ?: ""
                repository.insert(
                    SshKeyEntity(
                        name = name,
                        type = keyType,
                        publicKey = publicKey,
                        privateKey = cryptoManager.encrypt(privateKeyPem),
                        passphrase = passphrase?.let { cryptoManager.encrypt(it) }
                    )
                )
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun decryptPrivateKey(key: SshKeyEntity): String {
        return cryptoManager.decrypt(key.privateKey)
    }

    fun deleteKey(key: SshKeyEntity) {
        viewModelScope.launch { repository.delete(key) }
    }

    private fun detectKeyType(privateKey: String): String {
        return when {
            privateKey.contains("BEGIN RSA PRIVATE KEY") -> "RSA"
            privateKey.contains("BEGIN EC PRIVATE KEY") -> "ECDSA"
            privateKey.contains("BEGIN OPENSSH PRIVATE KEY") -> "Ed25519"
            privateKey.contains("BEGIN DSA PRIVATE KEY") -> "DSA"
            else -> "Unknown"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyManagementScreen(
    onBack: () -> Unit,
    viewModel: KeyViewModel = hiltViewModel()
) {
    val keys by viewModel.keys.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showGenerateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<SshKeyEntity?>(null) }
    var showPublicKeyDialog by remember { mutableStateOf<SshKeyEntity?>(null) }
    var showExportDialog by remember { mutableStateOf<SshKeyEntity?>(null) }

    // File picker state - must be at top-level composable scope
    var importKeyName by remember { mutableStateOf("") }
    var importPrivateKeyPem by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    var importSelectedFileName by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val content = inputStream?.bufferedReader()?.readText() ?: ""
                inputStream?.close()
                importPrivateKeyPem = content

                val cursor = context.contentResolver.query(it, null, null, null, null)
                val nameIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor?.moveToFirst()
                val fileName = if (nameIndex != null && nameIndex >= 0) cursor?.getString(nameIndex) else null
                cursor?.close()
                importSelectedFileName = fileName
                if (importKeyName.isBlank() && fileName != null) {
                    importKeyName = fileName.removeSuffix(".pem").removeSuffix(".key")
                }
            } catch (_: Exception) {
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH Keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Import Key")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showGenerateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Generate Key")
            }
        }
    ) { padding ->
        if (keys.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No SSH keys yet.\nTap + to generate or ↑ to import.")
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
                items(keys, key = { it.id }) { key ->
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
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
                                Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(key.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${key.type} · ${dateFormat.format(Date(key.createdAt))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { showPublicKeyDialog = key }) {
                                Icon(Icons.Default.ContentCopy, "Copy public key")
                            }
                            IconButton(onClick = { showExportDialog = key }) {
                                Icon(Icons.Default.Share, "Export key")
                            }
                            IconButton(onClick = { showDeleteDialog = key }) {
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

    // Generate Key Dialog
    if (showGenerateDialog) {
        var keyName by remember { mutableStateOf("") }
        var keyType by remember { mutableStateOf("RSA") }

        AlertDialog(
            onDismissRequest = { showGenerateDialog = false },
            title = { Text("Generate SSH Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = keyName,
                        onValueChange = { keyName = it },
                        label = { Text("Key Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Key Type", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            onClick = { keyType = "RSA" },
                            label = { Text("RSA") },
                            selected = keyType == "RSA"
                        )
                        FilterChip(
                            onClick = { keyType = "Ed25519" },
                            label = { Text("Ed25519") },
                            selected = keyType == "Ed25519"
                        )
                        FilterChip(
                            onClick = { keyType = "ECDSA" },
                            label = { Text("ECDSA") },
                            selected = keyType == "ECDSA"
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.generateKey(
                            keyName.ifBlank { "My $keyType Key" },
                            keyType,
                            if (keyType == "RSA") 4096 else 256
                        )
                        showGenerateDialog = false
                    }
                ) { Text("Generate") }
            },
            dismissButton = {
                TextButton(onClick = { showGenerateDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation
    showDeleteDialog?.let { key ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Key") },
            text = { Text("Delete \"${key.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteKey(key)
                    showDeleteDialog = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel") }
            }
        )
    }

    // Public Key Dialog
    showPublicKeyDialog?.let { key ->
        AlertDialog(
            onDismissRequest = { showPublicKeyDialog = null },
            title = { Text("Public Key") },
            text = {
                SelectionContainer {
                    Text(
                        key.publicKey,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(key.publicKey))
                    showPublicKeyDialog = null
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showPublicKeyDialog = null }) { Text("Close") }
            }
        )
    }

    // Import Key Dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importKeyName = ""; importPrivateKeyPem = ""; importPassphrase = ""; importSelectedFileName = null
            },
            title = { Text("Import SSH Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = importKeyName,
                        onValueChange = { importKeyName = it },
                        label = { Text("Key Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // File picker button
                    OutlinedButton(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.FileOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(importSelectedFileName ?: "Choose .pem file")
                    }

                    OutlinedTextField(
                        value = importPrivateKeyPem,
                        onValueChange = { importPrivateKeyPem = it },
                        label = { Text("Private Key (PEM)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        placeholder = { Text("-----BEGIN RSA PRIVATE KEY-----\n...") }
                    )
                    OutlinedTextField(
                        value = importPassphrase,
                        onValueChange = { importPassphrase = it },
                        label = { Text("Passphrase (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (importPrivateKeyPem.isNotBlank()) {
                            viewModel.importKey(
                                name = importKeyName.ifBlank { "Imported Key" },
                                privateKeyPem = importPrivateKeyPem.trim(),
                                passphrase = importPassphrase.ifBlank { null }
                            )
                            showImportDialog = false
                            importKeyName = ""; importPrivateKeyPem = ""; importPassphrase = ""; importSelectedFileName = null
                        }
                    }
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    importKeyName = ""; importPrivateKeyPem = ""; importPassphrase = ""; importSelectedFileName = null
                }) { Text("Cancel") }
            }
        )
    }

    // Export Key Dialog
    showExportDialog?.let { key ->
        val decryptedPrivateKey = remember(key) { viewModel.decryptPrivateKey(key) }

        AlertDialog(
            onDismissRequest = { showExportDialog = null },
            title = { Text("Export: ${key.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Public Key", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(
                            key.publicKey.ifBlank { "(not available)" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider()
                    Text("Private Key", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(
                            decryptedPrivateKey,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val shareText = buildString {
                        if (key.publicKey.isNotBlank()) {
                            appendLine("Public Key:")
                            appendLine(key.publicKey)
                            appendLine()
                        }
                        appendLine("Private Key:")
                        appendLine(decryptedPrivateKey)
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(intent, "Export SSH Key"))
                    showExportDialog = null
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = null }) { Text("Close") }
            }
        )
    }
}
