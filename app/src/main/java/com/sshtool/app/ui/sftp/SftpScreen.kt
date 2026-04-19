package com.sshtool.app.ui.sftp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.ChannelSftp
import com.sshtool.app.data.ssh.SshManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Vector
import javax.inject.Inject

data class SftpFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val permissions: String,
    val modifiedTime: Long
)

@HiltViewModel
class SftpViewModel @Inject constructor(
    private val sshManager: SshManager
) : ViewModel() {

    private var sftpChannel: ChannelSftp? = null

    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath

    private val _files = MutableStateFlow<List<SftpFile>>(emptyList())
    val files: StateFlow<List<SftpFile>> = _files

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun init(sessionId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                sftpChannel = withContext(Dispatchers.IO) {
                    sshManager.openSftpChannel(sessionId)
                }
                sftpChannel?.let {
                    val home = withContext(Dispatchers.IO) { it.home }
                    navigateTo(home)
                } ?: run {
                    _error.value = "Failed to open SFTP channel"
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
            _isLoading.value = false
        }
    }

    fun navigateTo(path: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val channel = sftpChannel ?: return@launch
                val entries = withContext(Dispatchers.IO) {
                    @Suppress("UNCHECKED_CAST")
                    val ls = channel.ls(path) as Vector<ChannelSftp.LsEntry>
                    ls.filter { it.filename != "." }
                        .map { entry ->
                            SftpFile(
                                name = entry.filename,
                                path = if (path.endsWith("/")) "$path${entry.filename}"
                                    else "$path/${entry.filename}",
                                isDirectory = entry.attrs.isDir,
                                size = entry.attrs.size,
                                permissions = entry.attrs.toString(),
                                modifiedTime = entry.attrs.mTime.toLong() * 1000
                            )
                        }
                        .sortedWith(compareByDescending<SftpFile> { it.isDirectory }
                            .thenBy { it.name.lowercase() })
                }
                _currentPath.value = path
                _files.value = entries
            } catch (e: Exception) {
                _error.value = e.message
            }
            _isLoading.value = false
        }
    }

    fun goUp() {
        val current = _currentPath.value
        if (current == "/") return
        val parent = current.substringBeforeLast("/").ifEmpty { "/" }
        navigateTo(parent)
    }

    fun deleteFile(file: SftpFile) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (file.isDirectory) sftpChannel?.rmdir(file.path)
                    else sftpChannel?.rm(file.path)
                }
                navigateTo(_currentPath.value)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun createDirectory(name: String) {
        viewModelScope.launch {
            try {
                val path = "${_currentPath.value}/$name"
                withContext(Dispatchers.IO) { sftpChannel?.mkdir(path) }
                navigateTo(_currentPath.value)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    override fun onCleared() {
        try { sftpChannel?.disconnect() } catch (_: Exception) {}
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(
    sessionId: String,
    onBack: () -> Unit,
    viewModel: SftpViewModel = hiltViewModel()
) {
    val currentPath by viewModel.currentPath.collectAsState()
    val files by viewModel.files.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var showNewDirDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        viewModel.init(sessionId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SFTP", style = MaterialTheme.typography.titleSmall)
                        Text(
                            currentPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.goUp() }) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Go Up")
                    }
                    IconButton(onClick = { viewModel.navigateTo(currentPath) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showNewDirDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                error?.let { err ->
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(files, key = { it.path }) { file ->
                        SftpFileItem(
                            file = file,
                            onClick = {
                                if (file.isDirectory) {
                                    if (file.name == "..") viewModel.goUp()
                                    else viewModel.navigateTo(file.path)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showNewDirDialog) {
        var dirName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDirDialog = false },
            title = { Text("New Directory") },
            text = {
                OutlinedTextField(
                    value = dirName,
                    onValueChange = { dirName = it },
                    label = { Text("Directory Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dirName.isNotBlank()) {
                        viewModel.createDirectory(dirName)
                        showNewDirDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDirDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SftpFileItem(
    file: SftpFile,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            if (!file.isDirectory && file.name != "..") {
                Text(formatFileSize(file.size))
            }
        },
        leadingContent = {
            Icon(
                imageVector = when {
                    file.name == ".." -> Icons.Default.ArrowUpward
                    file.isDirectory -> Icons.Default.Folder
                    else -> Icons.Default.InsertDriveFile
                },
                contentDescription = null,
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024 * 1024 * 1024)} GB"
}
