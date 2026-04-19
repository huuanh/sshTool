package com.sshtool.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshtool.app.data.preferences.AppPreferences
import com.sshtool.app.data.preferences.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch { appPreferences.setDarkTheme(value) }
    }

    fun setFontSize(value: Float) {
        viewModelScope.launch { appPreferences.setFontSize(value) }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { appPreferences.setKeepScreenOn(value) }
    }

    fun setBiometricLock(value: Boolean) {
        viewModelScope.launch { appPreferences.setBiometricLock(value) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Appearance Section
            SectionHeader("Appearance")

            SwitchPreference(
                title = "Dark Theme",
                subtitle = "Use dark color scheme",
                checked = settings.darkTheme,
                onCheckedChange = { viewModel.setDarkTheme(it) }
            )

            // Font Size
            ListItem(
                headlineContent = { Text("Font Size") },
                supportingContent = {
                    Column {
                        Text("${settings.fontSize.toInt()} sp")
                        Slider(
                            value = settings.fontSize,
                            onValueChange = { viewModel.setFontSize(it) },
                            valueRange = 8f..24f,
                            steps = 15
                        )
                    }
                }
            )

            HorizontalDivider()

            // Terminal Section
            SectionHeader("Terminal")

            SwitchPreference(
                title = "Keep Screen On",
                subtitle = "Prevent screen from turning off during sessions",
                checked = settings.keepScreenOn,
                onCheckedChange = { viewModel.setKeepScreenOn(it) }
            )

            HorizontalDivider()

            // Security Section
            SectionHeader("Security")

            SwitchPreference(
                title = "Biometric Lock",
                subtitle = "Require fingerprint to open app",
                checked = settings.biometricLock,
                onCheckedChange = { viewModel.setBiometricLock(it) }
            )

            HorizontalDivider()

            // About Section
            SectionHeader("About")

            ListItem(
                headlineContent = { Text("Version") },
                supportingContent = { Text("1.0.0") }
            )

            ListItem(
                headlineContent = { Text("SSH Library") },
                supportingContent = { Text("JSch (mwiede fork)") }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SwitchPreference(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}
