package com.sshtool.app.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sshtool.app.terminal.TerminalEmulator

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun TerminalScreen(
    connectionId: Long,
    onBack: () -> Unit,
    onOpenSftp: (String) -> Unit,
    onOpenSnippets: (String) -> Unit,
    onOpenPortForwards: (Long, String) -> Unit = { _, _ -> },
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val activeSessions by viewModel.activeSessions.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showMenu by remember { mutableStateOf(false) }
    var inputBuffer by remember { mutableStateOf("") }

    LaunchedEffect(connectionId) {
        if (uiState is TerminalUiState.Idle) {
            viewModel.connect(connectionId)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is TerminalUiState.Connected) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = snapshot?.title ?: "Terminal"
                    Text(title, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Active sessions indicator
                    if (activeSessions.size > 1) {
                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                            Text("${activeSessions.size}")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        val sessionId = viewModel.getCurrentSessionId()
                        DropdownMenuItem(
                            text = { Text("SFTP File Manager") },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = {
                                showMenu = false
                                sessionId?.let { onOpenSftp(it) }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Snippets") },
                            leadingIcon = { Icon(Icons.Default.Code, null) },
                            onClick = {
                                showMenu = false
                                sessionId?.let { onOpenSnippets(it) }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Port Forwarding") },
                            leadingIcon = { Icon(Icons.Default.SyncAlt, null) },
                            onClick = {
                                showMenu = false
                                sessionId?.let { onOpenPortForwards(connectionId, it) }
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            leadingIcon = { Icon(Icons.Default.Close, null) },
                            onClick = {
                                showMenu = false
                                viewModel.disconnect()
                                onBack()
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF11111B)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(TerminalEmulator.DEFAULT_BG_COLOR))
        ) {
            when (val state = uiState) {
                is TerminalUiState.Idle, is TerminalUiState.Connecting -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Connecting...",
                                color = Color(0xFFCDD6F4)
                            )
                        }
                    }
                }

                is TerminalUiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.connect(connectionId) }) {
                                Text("Retry")
                            }
                        }
                    }
                }

                is TerminalUiState.HostKeyChanged -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFFA000),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Host Key Changed!",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color(0xFFFFA000)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "The server's host key for ${state.host}:${state.port} has changed.\n" +
                                    "This could indicate a man-in-the-middle attack.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFCDD6F4)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Old: ${state.oldFingerprint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFBAC2DE)
                            )
                            Text(
                                "New: ${state.newFingerprint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFBAC2DE)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedButton(onClick = {
                                    viewModel.disconnect()
                                    onBack()
                                }) {
                                    Text("Reject")
                                }
                                Button(
                                    onClick = { viewModel.acceptHostKeyAndReconnect() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFFA000)
                                    )
                                ) {
                                    Text("Accept & Continue")
                                }
                            }
                        }
                    }
                }

                is TerminalUiState.Connected -> {
                    // Invisible text field to capture keyboard input
                    BasicTextField(
                        value = inputBuffer,
                        onValueChange = { newValue ->
                            val diff = if (newValue.length > inputBuffer.length) {
                                newValue.substring(inputBuffer.length)
                            } else ""
                            inputBuffer = newValue
                            if (diff.isNotEmpty()) {
                                viewModel.sendInput(diff)
                            }
                        },
                        modifier = Modifier
                            .size(1.dp)
                            .focusRequester(focusRequester)
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_ENTER -> {
                                            viewModel.sendSpecialKey(SpecialKey.ENTER)
                                            true
                                        }
                                        KeyEvent.KEYCODE_DEL -> {
                                            viewModel.sendSpecialKey(SpecialKey.BACKSPACE)
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_UP -> {
                                            viewModel.sendSpecialKey(SpecialKey.UP)
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                                            viewModel.sendSpecialKey(SpecialKey.DOWN)
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                                            viewModel.sendSpecialKey(SpecialKey.LEFT)
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            viewModel.sendSpecialKey(SpecialKey.RIGHT)
                                            true
                                        }
                                        KeyEvent.KEYCODE_TAB -> {
                                            viewModel.sendSpecialKey(SpecialKey.TAB)
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            },
                        textStyle = TextStyle(
                            color = Color.Transparent,
                            fontSize = 1.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(Color.Transparent)
                    )

                    // Terminal content
                    Box(modifier = Modifier.weight(1f)) {
                        TerminalRenderer(
                            snapshot = snapshot,
                            fontSize = settings.fontSize,
                            modifier = Modifier.fillMaxSize(),
                            onSizeChanged = { rows, cols ->
                                viewModel.resize(rows, cols)
                            },
                            onTap = {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }
                        )
                    }

                    // Extra keys bar
                    ExtraKeysBar(
                        onSpecialKey = { viewModel.sendSpecialKey(it) },
                        onCtrlKey = { viewModel.sendCtrlKey(it) },
                        onTextInput = { viewModel.sendInput(it) }
                    )
                }
            }
        }
    }
}
