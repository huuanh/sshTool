package com.example.termiusclone.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager

/**
 * Tiny wrapper around [BiometricPrompt]. Only used when the user opts in via
 * the "Biometric lock" preference.
 */
object AppLock {

    fun isEnabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("app_lock", false)

    fun isAvailable(ctx: Context): Boolean {
        val bm = BiometricManager.from(ctx)
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return bm.canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onFail: () -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock TermiusClone")
            .setSubtitle("Authenticate to access your hosts")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        val mainExec = androidx.core.content.ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, mainExec, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFail()
            }
        })
        prompt.authenticate(info)
    }
}
