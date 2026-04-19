package com.sshtool.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sshtool.app.data.preferences.AppPreferences
import com.sshtool.app.ui.navigation.AppNavigation
import com.sshtool.app.ui.theme.SshToolTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appPreferences: AppPreferences

    private var isAuthenticated = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check biometric setting synchronously to avoid flash of content
        val needsBiometric = runBlocking {
            try {
                appPreferences.settings.first().biometricLock
            } catch (e: Exception) {
                false
            }
        }

        if (!needsBiometric) {
            isAuthenticated.value = true
        }

        setContent {
            val authenticated by remember { isAuthenticated }

            SshToolTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (authenticated) {
                        AppNavigation()
                    } else {
                        BiometricLockScreen(
                            onAuthenticate = { showBiometricPrompt() }
                        )
                    }
                }
            }
        }

        if (needsBiometric) {
            showBiometricPrompt()
        }
    }

    private fun showBiometricPrompt() {
        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Biometric not available, allow access
            isAuthenticated.value = true
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("SSH Tool")
            .setSubtitle("Authenticate to continue")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        val biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    isAuthenticated.value = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User can retry via the button
                }
            }
        )
        biometricPrompt.authenticate(promptInfo)
    }
}

@Composable
private fun BiometricLockScreen(onAuthenticate: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.Fingerprint,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "SSH Tool is Locked",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "Authenticate to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onAuthenticate) {
                Text("Unlock")
            }
        }
    }
}
